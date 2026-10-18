package com.example.attendence

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Owns the admin radio independently of any Activity or unlocked UI session. */
class AttendanceStationService : Service() {
    data class State(val starting: Boolean = false, val running: Boolean = false, val message: String = "")
    companion object {
        const val STATE_CHANGED = "com.example.attendence.STATION_STATE_CHANGED"
        const val STOP = "com.example.attendence.STOP_STATION"
        private const val CHANNEL = "attendance_station"
        private const val NOTIFICATION = 1001
        @Volatile var state = State()
            private set
    }
    private lateinit var ble: BleAttendance
    private lateinit var store: AttendanceStore
    private var shuttingDown = false
    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val value = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            if (value == BluetoothAdapter.STATE_TURNING_OFF || value == BluetoothAdapter.STATE_OFF) {
                finish("Bluetooth is off. Turn it on and start the station again.")
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        ble = BleAttendance(applicationContext)
        store = AttendanceStore(applicationContext)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Attendance station", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows when this phone is collecting attendance over Bluetooth."
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
        ContextCompat.registerReceiver(this, bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finish("Campus station stopped. Received records are saved."); return START_NOT_STICKY }
        if (shuttingDown) return START_NOT_STICKY
        // Promote immediately, including a system restart, before asynchronous BLE setup.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION, notification("Starting attendance station…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(NOTIFICATION, notification("Starting attendance station…"))
            val profile = store.profile()
            check(profile?.admin == true) { "A professor profile is required to start the station." }
            if (Build.VERSION.SDK_INT >= 31) {
                check(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                    .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                    "Allow Nearby devices and start the station again."
                }
            }
            check(ble.supported && ble.enabled) { "Turn on Bluetooth and start the station again." }
            if (state.running || state.starting) {
                updateNotification(if (state.running) "Receiving attendance · screen can be locked" else "Starting attendance station…")
                return START_STICKY
            }
            publish(State(starting = true))
            ble.broadcast(requireNotNull(profile).room, receive = store::receive, received = {
                // No names or attendance details on the lock screen.
                publish(state.copy(message = "New attendance received and saved."))
            }, started = {
                publish(State(running = true))
                updateNotification("Receiving attendance · screen can be locked")
            }, failed = ::finish)
        } catch (error: Exception) {
            finish(error.message ?: "Couldn’t start the station. Open the app and try again.")
            return START_NOT_STICKY
        }
        return if (shuttingDown) START_NOT_STICKY else START_STICKY
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AttendanceStationService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_station_notification)
            .setContentTitle("Present attendance station")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop station", stop)
            .build()
    }
    private fun updateNotification(message: String) {
        // startForeground keeps the required service notification even when drawer permission is denied.
        startForeground(NOTIFICATION, notification(message))
    }
    private fun publish(value: State) {
        state = value
        sendBroadcast(Intent(STATE_CHANGED).setPackage(packageName))
    }
    private fun finish(message: String) {
        if (shuttingDown) return
        shuttingDown = true
        ble.stop()
        publish(State(message = message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        ble.stop()
        unregisterReceiver(bluetoothState)
        if (!shuttingDown) publish(State(message = "Campus station stopped. Received records are saved."))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
