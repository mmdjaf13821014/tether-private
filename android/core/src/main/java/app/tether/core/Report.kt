package app.tether.core

/**
 * Removes what identifies a person from text that goes into a problem report (logs, system values): phone numbers,
 * e-mail addresses, full Bluetooth / MAC addresses (only the last two bytes stay), IP addresses, IMEI- or serial-like
 * digit runs, long keys and tokens, and any [names] given (Bluetooth device names, the client's name, SIM and carrier
 * names). Timestamps, versions, build fingerprints, durations, counters and percentages stay as they are.
 *
 * Pure and idempotent: scrubbing a scrubbed text changes nothing. The same rules run in the iPhone app (TetherCore
 * `ReportScrubber`), so both apps' reports read alike.
 *
 * [names] shorter than 2 characters, and words a report needs ([keep], plus "iPhone", "Android", "Tether", …) are
 * never replaced.
 */
class ReportScrubber(names: Collection<String> = emptyList(), keep: Collection<String> = emptyList()) {
    private val names: List<Regex> = names.asSequence().map { it.trim() }.filter { it.length >= 2 }
        .filter { n -> GENERIC.none { it.equals(n, ignoreCase = true) } && keep.none { it.trim().equals(n, ignoreCase = true) } }
        .distinct().sortedByDescending { it.length }
        // Not inside a longer word: a SIM called "MCI" must not cut "MCInfo" apart.
        .map { Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(it) + "(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE) }
        .toList()

    /** [text] with everything personal replaced by `<number>`, `<email>`, `<ip>`, `<id>`, `<secret>`, `<name>`, `…XX:XX`. */
    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        // Build fingerprints hold digits, letters and slashes that look like keys: set aside, put back at the end.
        val kept = ArrayList<String>()
        var s = FINGERPRINT.replace(text) { m -> kept.add(m.value); "\u0000${kept.size - 1}\u0000" }
        for (n in names) s = n.replace(s, NAME)
        s = EMAIL.replace(s, "<email>")
        s = MAC.replace(s) { m -> "…" + m.value.takeLast(5).replace('-', ':') }
        s = LONG_COLON_HEX.replace(s, "<secret>")
        s = UUID.replace(s, "<id>")
        s = IPV6.replace(s) { m -> if (isIpv6(m.value)) "<ip>" else m.value }
        s = IPV4.replace(s) { m -> if (m.value in LOCAL_IPS || !m.value.split('.').all { (it.toIntOrNull() ?: 999) <= 255 }) m.value else "<ip>" }
        s = TAGGED_NUMBER.replace(s) { m -> m.groupValues[1] + "<number>" }
        s = INTERNATIONAL.replace(s) { m -> if (digits(m.value) in 7..15) "<number>" + trailing(m.value) else m.value }
        s = LONG_DIGITS.replace(s, "<id>")
        s = LOCAL.replace(s) { m -> if (digits(m.value) in 9..12) "<number>" else m.value }
        s = SECRET.replace(s) { m -> if (looksSecret(m.value)) "<secret>" else m.value }
        if (kept.isNotEmpty()) s = PLACEHOLDER.replace(s) { m -> kept.getOrNull(m.groupValues[1].toInt()) ?: "" }
        return s
    }

    /** Each of [lines] scrubbed. */
    fun scrubLines(lines: List<String>): List<String> = lines.map { scrub(it) }

    companion object {
        const val NAME = "<name>"
        /** Words that are never treated as a name, whatever someone called a device. */
        val GENERIC = listOf("iPhone", "iPad", "Android", "Android phone", "Phone", "Tether", "Tether Client", "Cellular", "SIM",
            "Unknown", "null", "none", "Mac", "Galaxy", "Samsung", "Pixel", "ios", "android", "client", "phone")

        private const val D = "[0-9\u06F0-\u06F9\u0660-\u0669]"   // ASCII, Persian and Arabic-Indic digits
        /** Bidi marks around numbers in right-to-left text count as separators. */
        private const val SEP = "[ \\-.()\u00A0\u2066-\u2069\u200E\u200F]"
        private val LOCAL_IPS = setOf("127.0.0.1", "0.0.0.0")

        private val PLACEHOLDER = Regex("\u0000(\\d+)\u0000")
        /** brand/product/device:release/id/incremental:type/tags (with an optional extra part). */
        private val FINGERPRINT = Regex("(?<![\\w/])[\\w.\\-]+/[\\w.\\-]+/[\\w.\\-]+:[\\w.\\-]+/[\\w.\\-]+/[\\w.\\-]+:[\\w.\\-]+/[\\w.,\\-]+")
        private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        /** Six bytes; a colon or dash may touch it ("addr:4C:…", "4C:…:77: interval") as long as no more hex follows. */
        private val MAC = Regex("(?<![0-9A-Fa-f\\-])(?<![0-9A-Fa-f][:\\-])(?:[0-9A-Fa-f]{2}([:\\-]))(?:[0-9A-Fa-f]{2}\\1){4}[0-9A-Fa-f]{2}(?![0-9A-Fa-f]|[:\\-][0-9A-Fa-f])")
        /** Colon-separated hex of 8+ bytes (key or certificate digests). */
        private val LONG_COLON_HEX = Regex("(?<![0-9A-Fa-f])(?<![0-9A-Fa-f]:)(?:[0-9A-Fa-f]{2}:){7,}[0-9A-Fa-f]{2}(?![0-9A-Fa-f]|:[0-9A-Fa-f])")
        private val IPV6 = Regex("(?<![\\w:.])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%\\w+)?(?![\\w:])")
        private val IPV4 = Regex("(?<![\\d.\\w])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.]*\\d)")
        /** Anything after tel: / sms: / number= is a number, whatever its shape. */
        private val TAGGED_NUMBER = Regex("((?:tel|sms|smsto|mms|sip):|(?:number|num|address|phone|msisdn)[\"']?\\s*[=:]\\s*[\"']?)\\+?$D(?:$SEP{0,2}$D){4,}", RegexOption.IGNORE_CASE)
        private val INTERNATIONAL = Regex("(?<![\\w+])\\+$D(?:$SEP{0,2}$D){6,16}\\)?")
        /** 14-22 digits in a row: an IMEI (15), IMSI, ICCID (19-20) or serial. */
        private val LONG_DIGITS = Regex("(?<!$D)$D{14,22}(?!$D)")
        /** A national number with its leading 0 (09123456789, 0912 345 6789, (021) 1234-5678). */
        private val LOCAL = Regex("(?<![\\w+.:/<])\\(?[0\u06F0\u0660]$D{2,4}\\)?(?:[ \\-\u00A0]?$D){6,10}(?![\\w])")
        private val UUID = Regex("(?<![0-9A-Fa-f\\-])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![0-9A-Fa-f\\-])")
        private val SECRET = Regex("(?<![A-Za-z0-9+/=_\\-])[A-Za-z0-9+/_\\-]{24,}={0,2}(?![A-Za-z0-9+/=_\\-])")

        private fun digits(s: String) = s.count { it.isDigit() }
        /** A matched number's closing parenthesis without an opening one stays. */
        private fun trailing(s: String) = if (s.endsWith(")") && s.count { it == '(' } < s.count { it == ')' }) ")" else ""

        private fun isIpv6(s: String): Boolean {
            val addr = s.substringBefore('%')
            if (addr == "::" || addr == "::1") return false
            val groups = addr.split(':')
            if (groups.any { it.length > 4 }) return false
            // "16:20:13" and "15:14:51:358" are times: an address has "::" or eight groups, and a hex letter or a long group.
            val shaped = groups.any { g -> g.length >= 3 || g.any { !it.isDigit() } }
            return shaped && (addr.contains("::") && groups.count { it.isNotEmpty() } >= 2 || groups.size == 8)
        }

        /** Long runs that mix letters and digits (base64, hex keys, tokens); long words and identifiers stay. */
        private fun looksSecret(s: String): Boolean {
            val body = s.trimEnd('=')
            val hasDigit = body.any { it.isDigit() }
            val hasLetter = body.any { it.isLetter() }
            if (!hasDigit || !hasLetter) return false
            val hex = body.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            if (hex) return true
            // A path or package name (several slashes or underscores between words) is no key.
            if (body.count { it == '/' } >= 3 && body.split('/').all { it.length in 1..20 }) return false
            val upper = body.count { it.isUpperCase() }; val lower = body.count { it.isLowerCase() }
            val digitsN = body.count { it.isDigit() }
            // Base64 of random bytes mixes cases and digits; an identifier like SOME_CONSTANT_NAME_2 does not.
            return (upper > 0 && lower > 0 && digitsN >= 2) || digitsN >= body.length / 4
        }
    }
}

/**
 * A plain-text problem report (the same format in the Android phone app, the Android client and the iPhone app):
 *
 * ```
 * === Tether problem report ===
 * App: Tether 1.2.2 (1020299) · Android phone app
 * Created: 2026-11-25 16:20:13 +03:30
 * Privacy: …
 *
 * ## Device
 * key: value
 * ```
 *
 * Every line goes through [scrubber] when added and the whole report once more in [build]. Log sections are cut from
 * their oldest lines so the report stays within [maxBytes].
 */
class ProblemReport(
    private val app: String,
    private val created: String,
    private val scrubber: ReportScrubber = ReportScrubber(),
    private val maxBytes: Int = MAX_BYTES,
) {
    private class Section(val title: String, val lines: MutableList<String>, val log: Boolean)
    private val sections = ArrayList<Section>()

    /** A section of `key: value` lines (and free lines); returns a writer for it. */
    fun section(title: String): Writer = Writer(Section(title, ArrayList(), log = false).also { sections.add(it) }.lines)

    /** A log section: [lines] oldest first; the oldest are dropped first when the report is too big. */
    fun log(title: String, lines: List<String>) {
        sections.add(Section(title, lines.map { scrubber.scrub(it) }.toMutableList().ifEmpty { mutableListOf("(empty)") }, log = true))
    }

    inner class Writer internal constructor(private val out: MutableList<String>) {
        /** `key: value`; a null or empty value is written as "–". */
        fun kv(key: String, value: Any?): Writer {
            val v = value?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: "–"
            out.add(scrubber.scrub("$key: $v")); return this
        }
        fun line(text: String): Writer { text.lines().forEach { out.add(scrubber.scrub(it)) }; return this }
        fun lines(text: List<String>): Writer { text.forEach { line(it) }; return this }
    }

    /** The report text, scrubbed again as a whole and at most [maxBytes] bytes in UTF-8. */
    fun build(): String = build(maxBytes)

    /** The same with another size limit (a shorter copy for the clipboard, say). */
    fun build(maxBytes: Int): String {
        val head = listOf(TITLE, "App: $app", "Created: $created", PRIVACY)
        val fixed = sections.filter { !it.log }
        val logs = sections.filter { it.log }
        fun bytes(l: String) = l.toByteArray(Charsets.UTF_8).size + 1
        val fixedBytes = head.sumOf { bytes(it) } + sections.sumOf { bytes("") + bytes("## ${it.title}") } +
            fixed.sumOf { s -> s.lines.sumOf { bytes(it) } }
        var budget = maxBytes - fixedBytes - 200 * logs.size
        // Each log section gets an equal share of what is left; what one does not use passes on to the next ones.
        val kept = HashMap<Section, List<String>>()
        for ((i, s) in logs.sortedBy { sec -> sec.lines.sumOf { bytes(it) } }.withIndex()) {
            val share = maxOf(0, budget / (logs.size - i))
            val out = ArrayDeque<String>()
            var used = 0
            for (l in s.lines.asReversed()) { val b = bytes(l); if (used + b > share) break; out.addFirst(l); used += b }
            val dropped = s.lines.size - out.size
            if (dropped > 0) out.addFirst("($dropped older lines left out to keep the report small)")
            kept[s] = out.toList()
            budget -= used
        }
        val sb = StringBuilder()
        head.forEach { sb.append(it).append('\n') }
        for (s in sections) {
            sb.append('\n').append("## ").append(s.title).append('\n')
            (kept[s] ?: s.lines).forEach { sb.append(it).append('\n') }
        }
        val text = scrubber.scrub(sb.toString())
        // A last guard (multi-byte text, a huge fixed section): cut at a line end.
        val all = text.toByteArray(Charsets.UTF_8)
        if (all.size <= maxBytes) return text
        val cut = String(all, 0, maxBytes, Charsets.UTF_8)
        return cut.substring(0, maxOf(0, cut.lastIndexOf('\n') + 1)) + "(cut to $maxBytes bytes)\n"
    }

    companion object {
        const val TITLE = "=== Tether problem report ==="
        const val PRIVACY = "Privacy: phone numbers, names, message text, e-mail, addresses and keys are removed. Check before posting."
        /** About 1 MB: small enough for an issue attachment and a chat app. */
        const val MAX_BYTES = 1_000_000

        /** The `Created:` value: local date and time with the UTC offset, e.g. `2026-11-25 16:20:13 +03:30`. */
        fun created(epochMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", java.util.Locale.US)
                .format(java.time.Instant.ofEpochMilli(epochMs).atZone(zone))

        /** The `App:` value. */
        fun appLine(versionName: String, versionCode: Long, which: String) = "Tether $versionName ($versionCode) · $which"
    }
}
