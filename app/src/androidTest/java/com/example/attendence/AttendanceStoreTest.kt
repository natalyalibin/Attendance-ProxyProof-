package com.example.attendence

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

/** Uses isolated preference files: never changes the installed user's profile/history. */
class AttendanceStoreTest {
    private lateinit var employeeContext: Context
    private lateinit var adminContext: Context
    private val files = mutableListOf<String>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolatedContext(): Context {
        val file = "test-attendance-${UUID.randomUUID()}"
        files += file
        return object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = base.getSharedPreferences(file, mode)
        }
    }
    @Before fun setup() {
        employeeContext = isolatedContext(); adminContext = isolatedContext()
        assertTrue(AttendanceStore(employeeContext).setup(Profile("Alex", "EMP-1", "KOCHI-HQ", false), "123456"))
        assertTrue(AttendanceStore(adminContext).setup(Profile("Admin", "ADM-1", "KOCHI-HQ", true), "123456"))
    }
    @After fun cleanup() { files.forEach { base.deleteSharedPreferences(it) } }
    @Test fun lostReceiptRetrySurvivesReopeningAndDoesNotDuplicate() {
        val employee = AttendanceStore(employeeContext)
        val entry = employee.prepareRecord(-60)
        assertTrue(employee.records().isEmpty())
        assertTrue(AttendanceStore(adminContext).receive(entry))
        // Simulate a lost ACK / process restart on both phones.
        val reopened = AttendanceStore(employeeContext)
        assertEquals(entry, reopened.prepareRecord(-70))
        val admin = AttendanceStore(adminContext)
        assertFalse(admin.receive(entry))
        assertEquals(1, admin.teamRecords().size)
        assertTrue(admin.teamRecords().single().receivedAt > 0)
        assertTrue(reopened.confirmRecord(entry.id).delivered)
        assertNull(AttendanceStore(employeeContext).pendingRecord())
        val checkOut = AttendanceStore(employeeContext).prepareRecord(-65)
        assertFalse(checkOut.checkIn)
        assertNotEquals(entry.id, checkOut.id)
        assertTrue(admin.receive(checkOut))
        assertEquals(2, AttendanceStore(adminContext).teamRecords().size)
    }
    @Test fun wrongOfficeIsRejectedWithoutSaving() {
        val entry = AttendanceStore(employeeContext).prepareRecord(-50).copy(office = "OTHER-HQ")
        try { AttendanceStore(adminContext).receive(entry); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        assertTrue(AttendanceStore(adminContext).teamRecords().isEmpty())
    }
    @Test fun sameIdWithDifferentContentsIsRejected() {
        val entry = AttendanceStore(employeeContext).prepareRecord(-50)
        val admin = AttendanceStore(adminContext)
        admin.receive(entry)
        try { admin.receive(entry.copy(checkIn = false)); fail("Expected conflict") } catch (_: IllegalArgumentException) { }
        assertTrue(admin.teamRecords().single().checkIn)
    }
    @Test fun employeeCannotAcceptTeamRecords() {
        val employee = AttendanceStore(employeeContext)
        try { employee.receive(employee.prepareRecord(-50)); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        assertTrue(employee.teamRecords().isEmpty())
    }
    @Test fun legacyRecordsRemainReadableAndAreNotClaimedAsDelivered() {
        employeeContext.getSharedPreferences("ignored", Context.MODE_PRIVATE).edit().putString("records",
            """[{"id":"old","employeeId":"EMP-1","timestamp":1000,"checkIn":true,"office":"KOCHI-HQ","rssi":-50}]""").commit()
        val employee = AttendanceStore(employeeContext)
        assertFalse(employee.records().single().delivered)
        assertFalse(employee.prepareRecord(-60).checkIn)
    }
}
