package com.example.attendence

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AttendanceProtocolTest {
    private fun entry(name: String = "Alex") = Attendance(UUID.randomUUID().toString(), "EMP-001", 1000, true, "KOCHI-HQ", -58, name)
    @Test fun roundTripHandlesUnicodeNamesAndMinimumMtu() {
        val original = entry("അലക്സ് 👋")
        val packets = AttendanceProtocol.packets(original)
        assertTrue(packets.all { it.size <= 20 })
        val receiver = AttendanceMessageBuffer()
        packets.dropLast(1).forEach { assertNull(receiver.accept(it)) }
        assertEquals(original, receiver.accept(packets.last()))
    }
    @Test fun partialTransferDoesNotProduceAttendance() {
        val receiver = AttendanceMessageBuffer()
        AttendanceProtocol.packets(entry()).dropLast(1).forEach { assertNull(receiver.accept(it)) }
    }
    @Test fun restartDiscardsPartialTransfer() {
        val receiver = AttendanceMessageBuffer()
        AttendanceProtocol.packets(entry()).take(3).forEach { receiver.accept(it) }
        val replacement = entry("New employee")
        var received: Attendance? = null
        AttendanceProtocol.packets(replacement).forEach { receiver.accept(it)?.let { value -> received = value } }
        assertEquals(replacement, received)
    }
    @Test(expected = IllegalArgumentException::class) fun dataWithoutStartIsRejected() {
        AttendanceMessageBuffer().accept(byteArrayOf(AttendanceProtocol.DATA, 10))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedMessagesAreRejected() {
        val receiver = AttendanceMessageBuffer()
        receiver.accept(byteArrayOf(AttendanceProtocol.START))
        repeat(55) { receiver.accept(byteArrayOf(AttendanceProtocol.DATA) + ByteArray(19)) }
    }
    @Test(expected = IllegalArgumentException::class) fun unknownProtocolVersionIsRejected() {
        val payload = AttendanceProtocol.encode(entry())
        payload[3] = 99
        AttendanceProtocol.decode(payload)
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedNamesAreRejected() { AttendanceProtocol.encode(entry("x".repeat(65))) }
    @Test(expected = IllegalArgumentException::class) fun trailingBytesAreRejected() {
        AttendanceProtocol.decode(AttendanceProtocol.encode(entry()) + byteArrayOf(1))
    }
}
