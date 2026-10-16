package com.example.attendence

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AttendanceStationServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    private fun awaitCondition(condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 20_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        assertTrue("Timed out waiting for station state", condition())
    }
    @Test fun stationSurvivesScreenOffAndStopsFromNotification() {
        // Never replace a user's configured profile; this smoke test needs a fresh test install.
        assumeTrue(AttendanceStore(context).profile() == null)
        if (Build.VERSION.SDK_INT >= 31) {
            shell("pm grant ${context.packageName} ${Manifest.permission.BLUETOOTH_CONNECT}")
            shell("pm grant ${context.packageName} ${Manifest.permission.BLUETOOTH_ADVERTISE}")
        }
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        assumeTrue(adapter != null && adapter.isEnabled && adapter.isMultipleAdvertisementSupported)
        val store = AttendanceStore(context)
        assertTrue(store.setup(Profile("Test station", "TEST-ADMIN", "TEST-OFFICE", true), "123456"))
        var activity: ActivityScenario<MainActivity>? = null
        try {
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity { ContextCompat.startForegroundService(it, Intent(it, AttendanceStationService::class.java)) }
            awaitCondition { AttendanceStationService.state.running }
            // Duplicate start must leave the existing station running.
            activity.onActivity { ContextCompat.startForegroundService(it, Intent(it, AttendanceStationService::class.java)) }
            activity.close(); activity = null
            shell("input keyevent 223") // SLEEP, independent of the user's current lock configuration.
            awaitCondition { !context.getSystemService(PowerManager::class.java).isInteractive }
            Thread.sleep(2_000)
            assertTrue(AttendanceStationService.state.running)
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
                .single { it.id == 1001 }.notification
            assertFalse(notification.extras.getCharSequence("android.text").toString().contains("Test station"))
            notification.actions.single { it.title.toString() == "Stop station" }.actionIntent.send()
            awaitCondition { !AttendanceStationService.state.running && !AttendanceStationService.state.starting }
            assertTrue(store.teamRecords().isEmpty())
        } finally {
            context.stopService(Intent(context, AttendanceStationService::class.java))
            shell("input keyevent 224") // WAKEUP; never bypass a device PIN.
            activity?.close()
            context.getSharedPreferences("attendance_local", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
