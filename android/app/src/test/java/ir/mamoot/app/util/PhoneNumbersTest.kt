package ir.mamoot.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumbersTest {

    @Test
    fun localFormat() {
        assertEquals("+989123456789", PhoneNumbers.toE164Iran("09123456789"))
    }

    @Test
    fun internationalFormats() {
        assertEquals("+989123456789", PhoneNumbers.toE164Iran("+98 912 345 6789"))
        assertEquals("+989123456789", PhoneNumbers.toE164Iran("00989123456789"))
        assertEquals("+989354567890", PhoneNumbers.toE164Iran("989354567890"))
    }

    @Test
    fun bareNationalFormat() {
        assertEquals("+989123456789", PhoneNumbers.toE164Iran("9123456789"))
    }

    @Test
    fun persianAndArabicDigits() {
        assertEquals("+989123456789", PhoneNumbers.toE164Iran("۰۹۱۲۳۴۵۶۷۸۹"))
        assertEquals("+989123456789", PhoneNumbers.toE164Iran("٠٩١٢٣٤٥٦٧٨٩"))
        assertEquals("123", PhoneNumbers.latinDigits("۱۲۳"))
    }

    @Test
    fun separatorsAndSpacesIgnored() {
        assertEquals("+989123456789", PhoneNumbers.toE164Iran("0912-345 67 89"))
    }

    @Test
    fun rejectsInvalid() {
        assertNull(PhoneNumbers.toE164Iran("02122334455")) // Tehran landline
        assertNull(PhoneNumbers.toE164Iran("+14155552671")) // foreign number
        assertNull(PhoneNumbers.toE164Iran("0912345678")) // too short
        assertNull(PhoneNumbers.toE164Iran("091234567890")) // too long
        assertNull(PhoneNumbers.toE164Iran(""))
        assertNull(PhoneNumbers.toE164Iran("abc"))
    }

    @Test
    fun displayGrouping() {
        assertEquals("۰۹۱۲ ۳۴۵ ۶۷۸۹", PhoneNumbers.formatLocalDisplay("+989123456789"))
        assertEquals("۰۹۱۲ ۳۴۵ ۶۷۸۹", PhoneNumbers.formatLocalDisplay("09123456789"))
    }
}
