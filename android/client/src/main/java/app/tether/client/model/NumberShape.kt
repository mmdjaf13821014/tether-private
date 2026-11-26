package app.tether.client.model

/**
 * Shapes of digit runs that are not phone numbers although a phone-number finder matches them: dates (also Persian
 * calendar ones), version numbers and IP addresses, amounts with thousands separators or decimals, times. The same
 * rules as the iPhone's TetherCore NumberShape; the message bubbles drop number links whose text has such a shape.
 */
object NumberShape {
    fun isNotAPhoneNumber(raw: String): Boolean {
        val s = asciiDigits(raw).trim()
        if (s.startsWith("+")) return false
        if (':' in s) return true   // times, "09:00-17:30"
        val g = s.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        val seps = s.filter { !it.isDigit() }.toSet()
        // Dates: y-m-d, d-m-y, m-d-y with -, / or . (and Persian calendar years 13xx / 14xx).
        if (g.size == 3 && seps.size == 1 && seps.first() in "-/." && isDate(g)) return true
        // Versions and IP addresses: three or more dot groups of at most three digits ("1.2.3", "192.168.1.10").
        if (g.size >= 3 && seps == setOf('.') && g.all { it.length <= 3 }) return true
        // Amounts: "1,250,000", "12,345,678.90", "1234567.89".
        if (Regex("""^\d{1,3}(,\d{3})+(\.\d{1,2})?$""").matches(s)) return true
        if (Regex("""^\d+\.\d{1,2}$""").matches(s)) return true
        return false
    }

    private fun isDate(g: List<String>): Boolean {
        val a = g[0].toIntOrNull() ?: return false; val b = g[1].toIntOrNull() ?: return false; val c = g[2].toIntOrNull() ?: return false
        fun md(m: Int, d: Int) = m in 1..12 && d in 1..31
        if (g[0].length == 4) return md(b, c)                                   // 2024-11-23, 1403/09/02
        if (g[2].length == 4 || (g[2].length == 2 && g[0].length <= 2 && g[1].length <= 2)) return md(b, a) || md(a, b)
        return false
    }

    /** Persian and Arabic-Indic digits as ASCII. */
    fun asciiDigits(s: String): String = buildString(s.length) {
        for (ch in s) append(when (ch) { in '۰'..'۹' -> '0' + (ch - '۰'); in '٠'..'٩' -> '0' + (ch - '٠'); else -> ch })
    }
}
