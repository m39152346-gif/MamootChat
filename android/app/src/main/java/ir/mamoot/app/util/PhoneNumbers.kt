package ir.mamoot.app.util

/**
 * Iranian mobile number normalization for phone-OTP login.
 * Pure JVM (unit-tested): accepts Persian/Arabic digits, local 09… format,
 * bare 9… format and +98 / 0098 international formats.
 */
object PhoneNumbers {
    private const val FA_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
    private const val AR_DIGITS = "٠١٢٣٤٥٦٧٨٩"

    fun latinDigits(input: String): String {
        val out = StringBuilder(input.length)
        for (c in input) {
            val fa = FA_DIGITS.indexOf(c)
            val ar = AR_DIGITS.indexOf(c)
            when {
                fa >= 0 -> out.append('0' + fa)
                ar >= 0 -> out.append('0' + ar)
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Returns E.164 ("+989123456789") for a valid Iranian mobile, else null. */
    fun toE164Iran(input: String): String? {
        var digits = latinDigits(input).filter { it.isDigit() }
        if (digits.startsWith("00")) digits = digits.drop(2)
        val national = when {
            digits.startsWith("98") && digits.length == 12 -> digits.drop(2)
            digits.startsWith("0") && digits.length == 11 -> digits.drop(1)
            digits.length == 10 -> digits
            else -> return null
        }
        // Iranian mobile numbers always start with 9 (09x…); landlines rejected.
        if (national.length != 10 || !national.startsWith("9")) return null
        return "+98$national"
    }

    /** "۰۹۱۲ ۳۴۵ ۶۷۸۹"-style friendly display for an E.164/local number. */
    fun formatLocalDisplay(input: String): String {
        val e164 = toE164Iran(input) ?: return input
        val local = "0" + e164.removePrefix("+98")
        val grouped = "${local.substring(0, 4)} ${local.substring(4, 7)} ${local.substring(7)}"
        return grouped.map { c -> if (c.isDigit()) FA_DIGITS[c - '0'] else c }.joinToString("")
    }
}
