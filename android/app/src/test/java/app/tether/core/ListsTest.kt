package app.tether.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ListsTest {
    @Test fun numberKeyMatchesIosMatchKey() {
        assertEquals("912345678", numberKey("+98 912 345 6789".dropLast(1)))
        assertEquals(numberKey("09121234567"), numberKey("+989121234567"))
        assertEquals(numberKey("۰۹۱۲۱۲۳۴۵۶۷"), numberKey("+98 912 123 4567"))
        assertEquals("7000", numberKey("7000"))
        assertEquals("", numberKey("Bank"))
    }

    @Test fun idRangesCompressRuns() {
        assertEquals("[]", idRanges(emptyList()).toString())
        assertEquals("[[5,5]]", idRanges(listOf(5)).toString())
        assertEquals("[[1,3],[7,8],[10,10]]", idRanges(listOf(8, 1, 2, 3, 7, 10, 2)).toString())
    }
}
