
package com.example.attendence

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Versioned, bounded messages; 20-byte writes also work at the minimum BLE MTU.
 */
object AttendanceProtocol {
    val characteristicUuid: UUID =
        UUID.fromString("da079b46-0796-4be4-a79c-d955443ed9b2")

    const val MAX_BYTES = 1024
    const val START: Byte = 0
    const val DATA: Byte = 1
    const val COMMIT: Byte = 2

    fun validate(entry: Attendance) {
        require(UUID.fromString(entry.id).toString() == entry.id)
        require(entry.employeeId.isNotBlank() && entry.employeeId.length <= 64)
        require(entry.employeeName.isNotBlank() && entry.employeeName.length <= 64)
        require(entry.office.matches(Regex("[A-Z0-9-]{4,24}")))
        require(entry.timestamp > 0 && entry.rssi in -127..20)
    }

    fun encode(entry: Attendance): ByteArray {
        validate(entry)

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(1)
            output.writeUTF(entry.id)
            output.writeUTF(entry.employeeId)
            output.writeUTF(entry.employeeName)
            output.writeLong(entry.timestamp)
            output.writeBoolean(entry.checkIn)
            output.writeUTF(entry.office)
            output.writeInt(entry.rssi)
        }

        return bytes.toByteArray().also {
            require(it.size <= MAX_BYTES)
        }
    }

    fun decode(bytes: ByteArray): Attendance {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)

        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 1) { "Unsupported protocol version" }

        val id = input.readUTF()
        val employeeId = input.readUTF()
        val name = input.readUTF()
        val time = input.readLong()
        val checkIn = input.readBoolean()
        val office = input.readUTF()
        val rssi = input.readInt()

        require(input.available() == 0) { "Unexpected trailing data" }

        return Attendance(
            id = id,
            employeeId = employeeId,
            timestamp = time,
            checkIn = checkIn,
            office = office,
            rssi = rssi,
            employeeName = name
        ).also {
            validate(it)
        }
    }

    fun packets(entry: Attendance): List<ByteArray> {
        val payload = encode(entry)
        val dataPackets = payload.toList().chunked(19).map { chunk ->
            byteArrayOf(DATA) + chunk.toByteArray()
        }

        return listOf(byteArrayOf(START)) +
                dataPackets +
                listOf(byteArrayOf(COMMIT))
    }
}

/**
 * One bounded transaction per peer.
 * The caller should discard this buffer on disconnect or timeout.
 */
class AttendanceMessageBuffer {
    private var bytes: ByteArrayOutputStream? = null

    fun accept(packet: ByteArray): Attendance? {
        require(packet.isNotEmpty() && packet.size <= 20)

        when (packet[0]) {
            AttendanceProtocol.START -> {
                require(packet.size == 1)
                bytes = ByteArrayOutputStream()
            }

            AttendanceProtocol.DATA -> {
                val buffer = requireNotNull(bytes) {
                    "Start a transaction first"
                }

                require(
                    packet.size > 1 &&
                            buffer.size() + packet.size - 1 <= AttendanceProtocol.MAX_BYTES
                )

                buffer.write(packet, 1, packet.size - 1)
            }

            AttendanceProtocol.COMMIT -> {
                require(packet.size == 1)

                val payload = requireNotNull(bytes) {
                    "Start a transaction first"
                }.toByteArray()

                bytes = null
                return AttendanceProtocol.decode(payload)
            }

            else -> error("Unknown packet")
        }

        return null
    }

    fun reset() {
        bytes = null
    }
}
