package ir.mamoot.app.util

import java.time.Duration
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

    fun isOnline(lastSeenIso: String?): Boolean {
        val instant = lastSeenIso?.let { parseInstant(it) } ?: return false
        return Duration.between(instant, Instant.now()).seconds < 90
    }

    fun presenceText(lastSeenIso: String?, isPublic: Boolean): String {
        if (!isPublic) return "آخرین بازدید پنهان است"
        val instant = lastSeenIso?.let { parseInstant(it) } ?: return ""
        val seconds = Duration.between(instant, Instant.now()).seconds
        return when {
            seconds < 90 -> "آنلاین"
            seconds < 3600 -> "آخرین بازدید ${faNum(maxOf(1, (seconds / 60).toInt()))} دقیقه پیش"
            seconds < 86_400 -> "آخرین بازدید امروز ساعت ${formatTime(lastSeenIso)}"
            seconds < 172_800 -> "آخرین بازدید دیروز ساعت ${formatTime(lastSeenIso)}"
            else -> "آخرین بازدید ${formatListTime(lastSeenIso)}"
        }
    }

    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "؟"
        if (parts.size == 1) return parts[0].take(2)
        return (parts[0].take(1) + parts[1].take(1))
    }

    /** Deterministic avatar color (ARGB) from a stable seed. */
    fun avatarColor(seed: String): Int {
        // iOS system colors: vivid on white, still readable with white initials.
        val palette = intArrayOf(
            0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFFF9500.toInt(), 0xFFFF2D55.toInt(),
            0xFFAF52DE.toInt(), 0xFF32ADE6.toInt(), 0xFFFF3B30.toInt(), 0xFF5856D6.toInt(),
        )
        var hash = 0
        for (ch in seed) hash = hash * 31 + ch.code
        return palette[Math.floorMod(hash, palette.size)]
    }
}
