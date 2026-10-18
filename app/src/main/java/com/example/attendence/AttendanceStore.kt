package com.example.attendence

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class Profile(val name: String, val studentId: String, val room: String, val admin: Boolean)
data class Attendance(
    val id: String, val studentId: String, val timestamp: Long, val checkIn: Boolean,
    val room: String, val rssi: Int, val employeeName: String = "",
    val delivered: Boolean = false, val receivedAt: Long = 0L
)

object AttendanceRules {
    fun nextIsCheckIn(records: List<Attendance>): Boolean = records.lastOrNull()?.checkIn != true
    fun officeUuid(code: String): UUID = UUID.nameUUIDFromBytes("attendance-room-v1:${code.trim().uppercase(java.util.Locale.ROOT)}".toByteArray(Charsets.UTF_8))
    fun workedMillis(records: List<Attendance>, now: Long): Long {
        var start: Long? = null
        var total = 0L
        records.forEach { record ->
            if (record.checkIn) start = record.timestamp
            else { start?.let { total += (record.timestamp - it).coerceAtLeast(0) }; start = null }
        }
        start?.let { total += (now - it).coerceAtLeast(0) }
        return total
    }
}

/** Local persistence boundary; replace with a Firebase-backed repository in a later iteration. */
class AttendanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("attendance_local", Context.MODE_PRIVATE)
    fun profile(): Profile? = prefs.getString("profile", null)?.let {
        val obj = JSONObject(it)
        Profile(obj.getString("name"), obj.getString("studentId"), obj.getString("room"), obj.getBoolean("admin"))
    }
    fun setup(profile: Profile, pin: String): Boolean {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val obj = JSONObject().put("name", profile.name).put("studentId", profile.studentId).put("room", profile.room).put("admin", profile.admin)
        return prefs.edit().putString("profile", obj.toString()).putString("salt", encode(salt)).putString("pin", encode(hash(pin, salt))).commit()
    }
    fun verify(pin: String): Boolean {
        val salt = prefs.getString("salt", null) ?: return false
        val expected = prefs.getString("pin", null) ?: return false
        return MessageDigest.isEqual(hash(pin, Base64.decode(salt, Base64.NO_WRAP)), Base64.decode(expected, Base64.NO_WRAP))
    }
    private fun toJson(entry: Attendance) = JSONObject()
        .put("id", entry.id).put("studentId", entry.studentId).put("timestamp", entry.timestamp)
        .put("checkIn", entry.checkIn).put("room", entry.room).put("rssi", entry.rssi)
        .put("employeeName", entry.employeeName).put("delivered", entry.delivered).put("receivedAt", entry.receivedAt)
    private fun fromJson(obj: JSONObject) = Attendance(
        obj.getString("id"), obj.getString("studentId"), obj.getLong("timestamp"),
        obj.getBoolean("checkIn"), obj.getString("room"), obj.getInt("rssi"),
        obj.optString("employeeName", ""), obj.optBoolean("delivered", false), obj.optLong("receivedAt", 0)
    )
    private fun readList(key: String): List<Attendance> {
        val arr = JSONArray(prefs.getString(key, "[]"))
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
    private fun encodeList(records: List<Attendance>): String = JSONArray().apply {
        records.forEach { put(toJson(it)) }
    }.toString()
    fun records(): List<Attendance> = readList("records")
    fun teamRecords(): List<Attendance> = readList("teamRecords")
    fun pendingRecord(): Attendance? = prefs.getString("pending", null)?.let { fromJson(JSONObject(it)) }

    /** Persist before transmitting. An interrupted transfer retries the same ID and action. */
    @Synchronized fun prepareRecord(rssi: Int): Attendance {
        pendingRecord()?.let { return it }
        val profile = requireNotNull(profile())
        check(!profile.admin)
        val entry = Attendance(UUID.randomUUID().toString(), profile.studentId, System.currentTimeMillis(),
            AttendanceRules.nextIsCheckIn(records()), profile.room, rssi, profile.name)
        check(prefs.edit().putString("pending", toJson(entry).toString()).commit())
        return entry
    }
    /** Receipt and pending removal are committed together; failure leaves a safe retry. */
    @Synchronized fun confirmRecord(id: String): Attendance {
        val entry = requireNotNull(pendingRecord())
        require(entry.id == id)
        val confirmed = entry.copy(delivered = true)
        val updated = records().filterNot { it.id == id } + confirmed
        check(prefs.edit().putString("records", encodeList(updated)).remove("pending").commit())
        return confirmed
    }
    /** A successful return is the admin's durable receipt. Duplicate IDs never create extra rows. */
    @Synchronized fun receive(entry: Attendance): Boolean {
        val profile = requireNotNull(profile())
        require(profile.admin && entry.room == profile.room)
        AttendanceProtocol.validate(entry)
        val records = teamRecords()
        val existing = records.find { it.id == entry.id }
        if (existing != null) {
            require(existing.copy(delivered = false, receivedAt = 0) == entry.copy(delivered = false, receivedAt = 0))
            return false
        }
        val received = entry.copy(delivered = true, receivedAt = System.currentTimeMillis())
        check(prefs.edit().putString("teamRecords", encodeList(records + received)).commit())
        return true
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
}
