// The daily check for new plant and seller data. The app ships with the data in its bundle; once a day it may
// download a newer set (version.json, plants.json, vendors.json and plant-regions.json) and uses it only when every
// check below passes. Otherwise it keeps what it has, so a broken or cut-off download never reaches the screen.
// The same as the iPhone app's DataUpdate.swift and SHA256.swift.
package garden.hereabouts.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

object DataUpdate {
    /** How long the app waits between checks. */
    val interval: Duration = Duration.ofHours(24)

    /** Whether it is time to check again: never checked, a day or more since the last check, or a last check in the future. */
    fun shouldCheck(last: Instant?, now: Instant): Boolean {
        if (last == null) return true
        // A last check in the future means the clock was moved back; check anyway, or updates would stop until then.
        val gap = Duration.between(last, now)
        return gap >= interval || gap.isNegative
    }

    /**
     * The downloaded data, or null when it must not be used. It is used only when: version.json reads, its schema is
     * 1, its hash differs from the data the app has, its date reads and is not older than the current date (skipped
     * when the current date cannot be read), the SHA-256 of the three files together is the hash in version.json
     * (capitals allowed), the files are valid UTF-8 and decode, every plant has an id, a common name, a scientific
     * name and a type, and there are plants, served regions and plant ranges, with no id used twice.
     */
    fun accept(version: ByteArray, plants: ByteArray, vendors: ByteArray, regions: ByteArray, current: DataVersion): AppData? {
        val next = try {
            json.decodeFromString(DataVersion.serializer(), strictUtf8(version) ?: return null)
        } catch (e: Exception) {
            return null
        }
        if (next.schema != 1) return null
        if (next.dataHash == current.dataHash) return null
        val nextDate = instant(next.dataDate) ?: return null
        val currentDate = instant(current.dataDate)
        if (currentDate != null && nextDate < currentDate) return null
        // lowercase() is Locale.ROOT, so a phone set to Turkish still lower-cases A to F the plain way.
        if (sha256Hex(plants + vendors + regions) != next.dataHash.lowercase()) return null
        val data = try {
            AppData.decode(
                strictUtf8(plants) ?: return null,
                strictUtf8(vendors) ?: return null,
                strictUtf8(regions) ?: return null,
                next,
            )
        } catch (e: Exception) {
            return null
        }
        if (data.plants.any { it.id.isEmpty() || it.common.isEmpty() || it.sci.isEmpty() || it.category.isEmpty() }) return null
        // The publisher has no check of its own, so a publish with no plants, no served regions or no plant ranges
        // would empty every installed app's list. Keep the current data instead.
        if (data.plants.isEmpty() || data.served.isEmpty() || data.geo.isEmpty()) return null
        // Two plants with the same id: the list, saved plants and reminders all look plants up by id.
        if (data.plants.map { it.id }.toSet().size != data.plants.size) return null
        return data
    }

    /** The bytes as text, or null when they are not valid UTF-8 (Kotlin's own String(bytes) would hide that). */
    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /**
     * An ISO 8601 moment as the publisher writes it ("2026-11-09T21:26:41-05:00"), as seconds since 1970, or null when
     * the text is not that shape: date, "T", hours and minutes, optional seconds with an optional fraction, then "Z"
     * or an offset like "+05:30". java.time's own readers accept other shapes too, so this reads it by hand.
     */
    internal fun instant(text: String): Double? {
        val s = text
        var i = 0
        fun digits(n: Int): Int? {
            if (i + n > s.length) return null
            var v = 0
            for (k in i until i + n) {
                if (s[k] !in '0'..'9') return null
                v = v * 10 + (s[k] - '0')
            }
            i += n
            return v
        }
        fun expect(c: Char): Boolean {
            if (i >= s.length || s[i] != c) return false
            i++
            return true
        }
        val year = digits(4) ?: return null
        if (!expect('-')) return null
        val month = digits(2) ?: return null
        if (!expect('-')) return null
        val day = digits(2) ?: return null
        if (!expect('T')) return null
        val hour = digits(2) ?: return null
        if (!expect(':')) return null
        val minute = digits(2) ?: return null
        var second = 0.0
        if (expect(':')) {
            second = (digits(2) ?: return null).toDouble()
            if (expect('.')) {
                var scale = 0.1
                val start = i
                while (i < s.length && s[i] in '0'..'9') {
                    second += (s[i] - '0') * scale
                    scale /= 10
                    i++
                }
                if (i == start) return null
            }
        }
        val offset: Long
        if (expect('Z')) {
            offset = 0
        } else if (i < s.length && (s[i] == '+' || s[i] == '-')) {
            val sign = if (s[i] == '-') -1 else 1
            i++
            val oh = digits(2) ?: return null
            if (!expect(':')) return null
            val om = digits(2) ?: return null
            if (oh > 23 || om > 59) return null
            offset = sign * (oh * 60L + om) * 60
        } else {
            return null
        }
        if (i != s.length) return null
        if (month !in 1..12 || day < 1 || day > daysIn(year, month) || hour > 23 || minute > 59 || second >= 60) return null
        val days = daysFromCivil(year.toLong(), month, day)
        return (days * 86400 + hour * 3600L + minute * 60L - offset).toDouble() + second
    }

    private fun daysIn(year: Int, month: Int): Int {
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        return intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[month - 1]
    }

    /** Days from 1970-01-01 to a Gregorian date (Howard Hinnant's days_from_civil). */
    private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private const val HEX = "0123456789abcdef"

    /** The SHA-256 of the bytes, as 64 lower-case hex digits, the way the publisher writes dataHash. */
    internal fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(64)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}
