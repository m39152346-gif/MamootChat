package ir.mamoot.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object UiUtil {
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    fun parseInstant(iso: String): Instant? {
        return try {
            OffsetDateTime.parse(iso).toInstant()
        } catch (e: Exception) {
            try {
                Instant.parse(iso)
            } catch (e2: Exception) {
                null
            }
        }
    }

    fun formatTime(iso: String): String {
        val instant = parseInstant(iso) ?: return ""
        val local = instant.atZone(ZoneId.systemDefault())
        return faDigits(timeFormatter.format(local))
    }

    fun formatListTime(iso: String): String {
        val instant = parseInstant(iso) ?: return ""
        val zone = ZoneId.systemDefault()
        val date = instant.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            date == today -> formatTime(iso)
            date == today.minusDays(1) -> "دیروز"
            else -> faDigits(dateFormatter.format(date))
        }
    }

    fun formatDayLabel(iso: String): String {
        val instant = parseInstant(iso) ?: return ""
        val zone = ZoneId.systemDefault()
        val date = instant.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            date == today -> "امروز"
            date == today.minusDays(1) -> "دیروز"
            else -> faDigits(dateFormatter.format(date))
        }
    }

    fun dayKey(iso: String): String {
        val instant = parseInstant(iso) ?: return iso.take(10)
        return instant.atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }

    fun faDigits(value: String): String {
        val persian = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        val sb = StringBuilder(value.length)
        for (ch in value) {
            sb.append(if (ch in '0'..'9') persian[ch - '0'] else ch)
        }
        return sb.toString()
    }

    fun faNum(n: Int): String = faDigits(n.toString())

    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "؟"
        if (parts.size == 1) return parts[0].take(2)
        return (parts[0].take(1) + parts[1].take(1))
    }

    /** Deterministic avatar color (ARGB) from a stable seed. */
    fun avatarColor(seed: String): Int {
        val palette = intArrayOf(
            0xFF5B5BD6.toInt(), 0xFF0E7490.toInt(), 0xFFB45309.toInt(), 0xFF15803D.toInt(),
            0xFFBE185D.toInt(), 0xFF0369A1.toInt(), 0xFFA16207.toInt(), 0xFF7C3AED.toInt(),
        )
        var hash = 0
        for (ch in seed) hash = hash * 31 + ch.code
        return palette[Math.floorMod(hash, palette.size)]
    }
}
