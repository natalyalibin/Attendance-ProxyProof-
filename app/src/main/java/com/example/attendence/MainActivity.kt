package com.example.attendence

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var store: AttendanceStore
    private lateinit var ble: BleAttendance
    private lateinit var body: LinearLayout
    private var unlocked = false
    private var busy = false
    private var broadcasting = false
    private var history = false
    private var notice = ""
    private var pending: (() -> Unit)? = null
    private var loginAttempts = 0
    private var retryAfter = 0L
    private val ink = Color.rgb(22, 43, 43)
    private val muted = Color.rgb(98, 116, 114)
    private val green = Color.rgb(19, 112, 90)
    private val bg = Color.rgb(245, 247, 242)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action = pending; pending = null
        if (result.values.all { it }) action?.invoke()
        else { notice = "Permission is needed to use the campus signal. Allow Nearby devices and Location in app settings, then retry."; dashboard() }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notification permission is optional for a foreground service, not a BLE permission.
        startAdminService()
    }
    private val stationState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (unlocked && store.profile()?.admin == true) { syncStationState(); dashboard() }
        }
    }
    private fun syncStationState() {
        val state = AttendanceStationService.state
        busy = state.starting; broadcasting = state.running
        if (state.message.isNotEmpty()) notice = state.message
    }
    private fun startAdminService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, AttendanceStationService::class.java))
            busy = true; notice = ""; dashboard()
        } catch (_: Exception) {
            busy = false; notice = "Couldn’t start background collection. Keep the app open and try again."; dashboard()
        }
    }
    private fun startAdminStation() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startAdminService()
    }
    private val bluetoothEnable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val action = pending; pending = null
        if (it.resultCode == Activity.RESULT_OK) action?.invoke()
        else { notice = "Turn on Bluetooth to continue."; dashboard() }
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                val rows = if (store.profile()?.admin == true) store.teamRecords() else store.records()
                val csv = "Student ID,Name,Action,Student time,Campus,Signal dBm,Delivery,Professor received time,Record ID\n" + rows.joinToString("\n") { r ->
                    listOf(r.studentId, r.employeeName, if (r.checkIn) "Check in" else "Check out", format(r.timestamp, "yyyy-MM-dd'T'HH:mm:ssXXX"), r.room, r.rssi.toString(), if (r.delivered) "Received by professor" else "Local only", if (r.receivedAt > 0) format(r.receivedAt, "yyyy-MM-dd'T'HH:mm:ssXXX") else "", r.id).joinToString(",") { value ->
                        val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
                        "\"${safe.replace("\"", "\"\"")}\""
                    }
                }
                requireNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(csv) }
                notice = "Attendance exported successfully."
            } catch (_: Exception) { notice = "Couldn’t export this file. Please try another destination." }
            if (unlocked) dashboard()
        }
    }
    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                stopBle(); notice = "Bluetooth was turned off. Turn it on to continue."; if (unlocked) dashboard()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AttendanceStore(this); ble = BleAttendance(this)
        ContextCompat.registerReceiver(this, bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        if (store.profile() == null) setup() else login()
    }
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, stationState, IntentFilter(AttendanceStationService.STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (unlocked && store.profile()?.admin == true) { syncStationState(); dashboard() }
    }
    override fun onStop() {
        super.onStop()
        unregisterReceiver(stationState)
        // Only employee transfers belong to this Activity. The admin service keeps running.
        if (store.profile()?.admin != true) {
            val wasActive = busy
            stopBle()
            if (wasActive) notice = "Transfer paused when you left the app. Retry any pending attendance."
        }
        if (unlocked && store.profile()?.admin != true) dashboard()
    }
    override fun onDestroy() { ble.stop(); unregisterReceiver(bluetoothState); super.onDestroy() }
    private fun stopBle() { ble.stop(); busy = false }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun page(eyebrow: String, title: String, subtitle: String) {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(28)) }
        scroll.addView(body)
        val frame = FrameLayout(this).apply { setBackgroundColor(bg); addView(scroll) }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(frame)
        ViewCompat.requestApplyInsets(frame)
        label(body, "◉  P R E S E N T", 18, green, true)
        space(body, 30)
        label(body, eyebrow.uppercase(Locale.ROOT), 11, muted, true)
        label(body, title, 32, ink, true)
        label(body, subtitle, 15, muted)
        space(body, 24)
    }
    private fun label(parent: LinearLayout, value: String, size: Int = 16, color: Int = ink, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value; textSize = size.toFloat(); setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }
    private fun space(parent: LinearLayout, height: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun card(parent: LinearLayout = body, color: Int = Color.WHITE): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = shape(color); setPadding(dp(20), dp(20), dp(20), dp(20))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    private fun button(parent: LinearLayout, title: String, secondary: Boolean = false, enabled: Boolean = true, action: () -> Unit): Button = Button(this).apply {
        text = title; isAllCaps = false; textSize = 15f; isEnabled = enabled
        setTextColor(if (secondary) green else Color.WHITE)
        background = shape(if (!enabled) muted else if (secondary) Color.rgb(229, 239, 230) else green, 14)
        parent.addView(this, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8); bottomMargin = dp(4) })
        setOnClickListener { action() }
    }
    private fun field(parent: LinearLayout, title: String, hint: String, pin: Boolean = false): EditText {
        label(parent, title, 13, ink, true)
        return EditText(this).apply {
            this.hint = hint; textSize = 16f; setTextColor(ink); setHintTextColor(muted); setSingleLine()
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = shape(bg, 10)
            inputType = if (pin) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(android.text.InputFilter.LengthFilter(if (pin) 6 else 64))
            parent.addView(this, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(16) })
        }
    }
    private fun setup() {
        page("Local prototype", "A better way\nto be present.", "Set up this phone once. No account or internet connection needed.")
        val panel = card()
        label(panel, "Use this phone as", 16, ink, true)
        val roles = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val employee = RadioButton(this).apply { id = View.generateViewId(); text = "Student · check in and out"; setTextColor(ink); minHeight = dp(48) }
        val admin = RadioButton(this).apply { id = View.generateViewId(); text = "Professor · collect attendance"; setTextColor(ink); minHeight = dp(48) }
        roles.addView(employee); roles.addView(admin); roles.check(employee.id); panel.addView(roles)
        space(panel, 16)
        val name = field(panel, "Your name", "e.g. Alex Morgan")
        val studentId = field(panel, "Student / professor ID", "e.g. STU-001")
        val room = field(panel, "Shared campus code", "e.g. CAMPUS-01")
        label(panel, "Enter the same campus code on the professor and student phones.", 12, muted)
        val pin = field(panel, "Create a 6-digit PIN", "6 digits", true)
        val confirm = field(panel, "Confirm PIN", "Re-enter PIN", true)
        val error = label(panel, "", 13, Color.rgb(170, 57, 49))
        button(panel, "Set up this phone") {
            val officeCode = room.text.toString().trim().uppercase(Locale.ROOT)
            when {
                name.text.isBlank() || studentId.text.isBlank() -> error.text = "Enter your name and ID."
                !officeCode.matches(Regex("[A-Z0-9-]{4,24}")) -> error.text = "Campus code: use 4–24 letters, numbers or hyphens."
                !pin.text.toString().matches(Regex("[0-9]{6}")) -> error.text = "Choose a 6-digit PIN."
                pin.text.toString() != confirm.text.toString() -> error.text = "The PINs don’t match."
                else -> {
                    if (store.setup(Profile(name.text.toString().trim(), studentId.text.toString().trim(), officeCode, roles.checkedRadioButtonId == admin.id), pin.text.toString())) { unlocked = true; dashboard() }
                    else error.text = "Couldn’t save setup. Please try again."
                }
            }
        }
        label(body, "Prototype access is self-configured on each phone. The campus code identifies a signal; it is not a secure credential.", 12, muted)
    }
    private fun login() {
        val profile = store.profile() ?: return setup()
        page("Welcome back", "Hello, ${profile.name.substringBefore(' ')}.", "Unlock your ${if (profile.admin) "campus station" else "attendance"} with your local PIN.")
        val panel = card()
        val pin = field(panel, "Your PIN", "6 digits", true)
        val error = label(panel, "", 13, Color.rgb(170, 57, 49))
        button(panel, "Unlock") {
            if (android.os.SystemClock.elapsedRealtime() < retryAfter) { error.text = "Too many attempts. Wait 30 seconds before trying again." }
            else if (store.verify(pin.text.toString())) { unlocked = true; loginAttempts = 0; dashboard() }
            else { loginAttempts++; if (loginAttempts >= 5) { retryAfter = android.os.SystemClock.elapsedRealtime() + 30_000; loginAttempts = 0 }; error.text = "Incorrect PIN. Please try again."; pin.text.clear() }
        }
        label(body, "${profile.studentId}  •  ${profile.room}\nAttendance stays on the student and professor phones. Keep your PIN safe; recovery is not available in this prototype.", 13, muted)
    }
    private fun dashboard() {
        if (!unlocked) return
        val profile = store.profile() ?: return
        if (profile.admin) {
            busy = AttendanceStationService.state.starting
            broadcasting = AttendanceStationService.state.running
        }
        page(if (profile.admin) "Campus station" else format(System.currentTimeMillis(), "EEEE, d MMMM"), if (profile.admin) "Bring your class\nwithin reach." else if (history) "Your attendance." else "Hello, ${profile.name.substringBefore(' ')}.", "${profile.studentId}  /  ${profile.room}")
        if (notice.isNotEmpty()) label(card(color = Color.rgb(235, 240, 221)), notice, 14)
        if (profile.admin) adminPanel(profile) else employeePanel()
        space(body, 8)
        button(body, "Lock app", true) { stopBle(); unlocked = false; history = false; notice = ""; login() }
        label(body, "LOCAL PROTOTYPE  ·  No cloud sync", 11, muted)
    }
    private fun adminPanel(profile: Profile) {
        val panel = card(color = Color.rgb(225, 238, 225))
        label(panel, if (broadcasting) "●  RECEIVING IN BACKGROUND" else if (busy) "◌  STARTING STATION" else "○  STATION OFFLINE", 12, green, true)
        label(panel, profile.room, 30, ink, true)
        label(panel, if (broadcasting) "Collection stays on when you switch apps, lock the app, or lock the screen. Use Stop station here or in the notification to end collection." else "Start your station to collect check-ins and check-outs over Bluetooth.", 16, muted)
        button(panel, if (broadcasting || busy) "Stop station" else "Start station") {
            if (broadcasting || busy) {
                stopService(Intent(this, AttendanceStationService::class.java))
            } else withBluetooth(true) { startAdminStation() }
        }
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            label(panel, "Notifications are off. Enable them to see the station status and Stop button in your notification drawer.", 12, muted)
            button(panel, "Notification settings", true) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }
        }
        val records = store.teamRecords()
        val latest = records.groupBy { it.studentId }.values.map { it.last() }
        val today = format(System.currentTimeMillis(), "yyyy-MM-dd")
        val summary = card()
        label(summary, "CLASS ATTENDANCE", 11, muted, true)
        label(summary, "${latest.count { it.checkIn }} checked in", 28, ink, true)
        label(summary, "${latest.size} students recorded · ${records.count { format(it.receivedAt, "yyyy-MM-dd") == today }} events received today", 13, muted)
        if (records.isEmpty()) label(summary, "No attendance received yet. Share campus code ${profile.room} and ask a student to scan using the updated app.", 15, muted)
        latest.sortedBy { it.employeeName.lowercase(Locale.ROOT) }.forEach { entry ->
            space(summary, 8)
            label(summary, "${if (entry.checkIn) "●" else "○"}  ${entry.employeeName}", 16, ink, true)
            label(summary, "${entry.studentId} · ${if (entry.checkIn) "Checked in" else "Checked out"}\n${format(entry.timestamp, "dd MMM, h:mm a")} (student time)", 12, muted)
        }
        if (records.isNotEmpty()) {
            val list = card()
            label(list, if (history) "Received history (${records.size})" else "Recently received", 18, ink, true)
            (if (history) records.asReversed() else records.takeLast(5).asReversed()).forEach { entry ->
                label(list, "${entry.employeeName} · ${if (entry.checkIn) "Check in" else "Check out"}", 15, ink, true)
                label(list, "${entry.studentId} · ${format(entry.timestamp, "dd MMM, h:mm a")}\nReceived ${format(entry.receivedAt, "dd MMM, h:mm a")}", 12, muted)
                space(list, 8)
            }
            button(body, if (history) "Show recent records" else "View all received records", true, !busy) { history = !history; dashboard() }
            button(body, "Export class attendance as CSV", true, !busy) { export.launch("class-attendance-${format(System.currentTimeMillis(), "yyyy-MM-dd")}.csv") }
        }
        label(body, "Records are stored on this professor phone, even after the station stops. Keep Bluetooth on. Collection continues while exporting or with the screen locked. After a phone restart or force-stop, open the app and start the station again. Use one station per campus code.", 12, muted)
    }
    private fun employeePanel() {
        val records = store.records()
        val pendingEntry = store.pendingRecord()
        val nextIn = AttendanceRules.nextIsCheckIn(records)
        if (pendingEntry != null) {
            val pendingCard = card(color = Color.rgb(255, 240, 208))
            label(pendingCard, "Awaiting professor receipt", 17, ink, true)
            label(pendingCard, "${if (pendingEntry.checkIn) "Check-in" else "Check-out"} · ${format(pendingEntry.timestamp, "dd MMM, h:mm a")}\nThe professor may already have received this record. Retry to confirm; the same record won’t be counted twice.", 13, muted)
        }
        if (!history) {
            val hero = card(color = Color.rgb(225, 238, 225))
            label(hero, if (nextIn) "○  YOU’RE CHECKED OUT" else "●  YOU’RE CHECKED IN", 12, green, true)
            label(hero, if (busy) "Connecting to\nyour campus…" else if (nextIn) "Ready for\na good day?" else "You’re checked\nin on campus.", 30, ink, true)
            label(hero, if (busy) "Keep this screen open while we find the station and wait for its receipt (up to 45 seconds)." else "Scan, send attendance, and receive confirmation from the professor phone.", 15, muted)
            button(hero, if (busy) "Cancel transfer" else if (pendingEntry != null) "Retry pending attendance" else if (nextIn) "Scan & check in" else "Scan & check out") {
                if (busy) { stopBle(); notice = if (store.pendingRecord() != null) "Transfer cancelled. Retry the pending record to confirm delivery." else "Scan cancelled. No attendance was created."; dashboard() }
                else withBluetooth(false) {
                    busy = true; notice = ""; dashboard()
                    ble.submit(store.profile()!!.room, prepare = store::prepareRecord, delivered = { id ->
                        busy = false
                        notice = try {
                            val entry = store.confirmRecord(id)
                            "${if (entry.checkIn) "Checked in" else "Checked out"} at ${format(entry.timestamp, "h:mm a")}. Received by professor and saved on both phones."
                        } catch (_: Exception) { "Professor received attendance, but the local receipt couldn’t be saved. Retry the pending record safely." }
                        dashboard()
                    }, failed = { message -> busy = false; notice = message; dashboard() })
                }
            }
            val stats = card()
            label(stats, "YOUR ACTIVITY", 11, muted, true)
            val minutes = AttendanceRules.workedMillis(records, System.currentTimeMillis()) / 60_000
            label(stats, "${minutes / 60}h ${minutes % 60}m", 30, ink, true)
            label(stats, "Total recorded time · all sessions\n${records.count { it.checkIn }} check-ins on this device", 13, muted)
        }
        val list = card()
        label(list, if (history) "All records (${records.size})" else "Recent activity", 18, ink, true)
        if (records.isEmpty()) label(list, "Your first check-in starts here.\nFind the campus signal to add a record.", 15, muted)
        (if (history) records.asReversed() else records.takeLast(4).asReversed()).forEach { entry ->
            label(list, "${if (entry.checkIn) "↘  Check in" else "↗  Check out"}   ·   ${format(entry.timestamp, "h:mm a")}", 16, ink, true)
            label(list, "${format(entry.timestamp, "dd MMM yyyy")}  ·  ${entry.room}\n${if (entry.delivered) "Received by professor" else "Local only · recorded before sharing was added"}", 12, muted)
            space(list, 10)
        }
        button(body, if (history) "Back to check-in" else "View attendance history", true, !busy) { history = !history; notice = ""; dashboard() }
        if (history && records.isNotEmpty()) button(body, "Export records as CSV", true) { export.launch("attendance-${format(System.currentTimeMillis(), "yyyy-MM-dd")}.csv") }
    }
    private fun withBluetooth(admin: Boolean, action: () -> Unit) {
        if (!ble.supported) { notice = "This device doesn’t support Bluetooth. Use a physical Android phone."; dashboard(); return }
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            required += Manifest.permission.BLUETOOTH_CONNECT
            required += if (admin) Manifest.permission.BLUETOOTH_ADVERTISE else Manifest.permission.BLUETOOTH_SCAN
        }
        // Attendance establishes presence at an room, so do not assert neverForLocation.
        if (!admin) { required += Manifest.permission.ACCESS_FINE_LOCATION; required += Manifest.permission.ACCESS_COARSE_LOCATION }
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { pending = { withBluetooth(admin, action) }; permissions.launch(required.toTypedArray()); return }
        if (!ble.enabled) { pending = { withBluetooth(admin, action) }; bluetoothEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        if (!admin) {
            val manager = getSystemService(LocationManager::class.java)
            val enabled = if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (!enabled) {
                notice = "Turn on Location services, then retry the scan. Android uses this for BLE-based campus presence."
                dashboard()
                button(body, "Open Location settings", true) { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                return
            }
        }
        action()
    }
    private fun format(timestamp: Long, pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}
