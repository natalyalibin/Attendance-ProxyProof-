package com.example.attendence

import org.junit.Assert.*
import org.junit.Test

class AttendanceRulesTest {
    private fun event(time: Long, inside: Boolean) = Attendance("id", "EMP-1", time, inside, "OFFICE", -55)
    @Test fun firstActionIsCheckIn() { assertTrue(AttendanceRules.nextIsCheckIn(emptyList())) }
    @Test fun actionsAlternateAcrossMidnight() {
        val records = listOf(event(100, true))
        assertFalse(AttendanceRules.nextIsCheckIn(records))
        assertTrue(AttendanceRules.nextIsCheckIn(records + event(200, false)))
    }
    @Test fun officeCodesAreCaseInsensitiveAndTrimmed() {
        assertEquals(AttendanceRules.officeUuid("  kochi-hq "), AttendanceRules.officeUuid("KOCHI-HQ"))
        assertNotEquals(AttendanceRules.officeUuid("KOCHI-HQ"), AttendanceRules.officeUuid("OTHER-HQ"))
    }
    @Test fun durationIncludesCompletedAndOpenSessions() {
        assertEquals(250L, AttendanceRules.workedMillis(listOf(event(100, true), event(200, false), event(300, true)), 450))
    }
    @Test fun clockMovingBackwardsNeverCreatesNegativeDuration() {
        assertEquals(0L, AttendanceRules.workedMillis(listOf(event(200, true), event(100, false)), 300))
        assertEquals(0L, AttendanceRules.workedMillis(listOf(event(200, true)), 100))
    }
}
