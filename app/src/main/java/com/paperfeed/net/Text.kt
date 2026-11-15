package com.paperfeed.net

import androidx.core.text.HtmlCompat
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Text {
    private val doiRegex = Regex("""10\.\d{4,9}/[^\s"<>?#&]+""")

    fun findDoi(vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull { c -> c?.let { doiRegex.find(it)?.value } }?.let(::normalizeDoi)

    fun normalizeDoi(doi: String): String =
        doi.trim()
            .removePrefix("doi:")
            .replace(Regex("^https?://(dx\\.)?doi\\.org/", RegexOption.IGNORE_CASE), "")
            .trimEnd('.', ',', ';', ')')
            .lowercase(Locale.ROOT)

    fun keyFor(doi: String?, url: String?, title: String): String =
        doi?.let { "doi:${normalizeDoi(it)}" }
            ?: url?.let { "url:${it.substringBefore("?dgcid=").substringBefore("?af=")}" }
            ?: "title:${title.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }}"

    private val superscripts = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶',
        '7' to '⁷', '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '−' to '⁻', '=' to '⁼',
        '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ', '*' to '*',
    )
    private val subscripts = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆',
        '7' to '₇', '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '−' to '₋', '=' to '₌',
        '(' to '₍', ')' to '₎',
    )

    private fun mapScript(inner: String, table: Map<Char, Char>, fallbackPrefix: String): String {
        val plain = HtmlCompat.fromHtml(inner, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
        return if (plain.all { it in table }) plain.map { table.getValue(it) }.joinToString("")
        else "$fallbackPrefix$plain"
    }

    private const val PARA = "\u2029"
    private val markup = Regex("<[a-zA-Z/!]|&[a-zA-Z#0-9]+;")

    /** Converts HTML / JATS markup to readable plain text, keeping paragraph breaks. */
    fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw
            .replace(Regex("<(jats:)?(sup)>(.*?)</(jats:)?sup>", RegexOption.DOT_MATCHES_ALL)) {
                mapScript(it.groupValues[3], superscripts, "^")
            }
            .replace(Regex("<(jats:)?(sub|inf)>(.*?)</(jats:)?(sub|inf)>", RegexOption.DOT_MATCHES_ALL)) {
                mapScript(it.groupValues[3], subscripts, "_")
            }
            .replace(Regex("<jats:title>.*?</jats:title>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("</(jats:)?p>|<br\\s*/?>", RegexOption.IGNORE_CASE), PARA)
            .replace(Regex("</?jats:[^>]*>"), "")
            .replace(Regex("<math.*?</math>", RegexOption.DOT_MATCHES_ALL), "")
        // Decode twice for feeds that double-escape markup (&lt;p&gt;). On the second pass the
        // newlines are real paragraph breaks from the first pass, so they become <br> again.
        for (pass in 0..1) {
            if (!markup.containsMatchIn(s)) break
            val html = if (pass == 0) s.replace(PARA, "<br>") else s.replace("\n", "<br>")
            s = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        }
        s = latexToUnicode(s.replace(PARA, "\n"))
            .replace(Regex("[ \\t\\x0B\\f\\r\\u00A0]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return s.ifBlank { null }
    }

    private val texSymbols = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν",
        "xi" to "ξ", "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "phi" to "φ", "varphi" to "φ",
        "chi" to "χ", "psi" to "ψ", "omega" to "ω", "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ",
        "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "hbar" to "ħ", "ell" to "ℓ", "pm" to "±", "mp" to "∓", "times" to "×", "cdot" to "·", "approx" to "≈",
        "sim" to "∼", "simeq" to "≃", "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "neq" to "≠",
        "ne" to "≠", "infty" to "∞", "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
        "leftrightarrow" to "↔", "partial" to "∂", "nabla" to "∇", "sqrt" to "√", "propto" to "∝",
        "langle" to "⟨", "rangle" to "⟩", "gg" to "≫", "ll" to "≪", "prime" to "′", "circ" to "°",
        "odot" to "⊙", "dagger" to "†", "uparrow" to "↑", "downarrow" to "↓", "quad" to " ", "left" to "",
        "right" to "", "bar" to "", "overline" to "", "tilde" to "", "hat" to "", "mathrm" to "", "text" to "",
    )
    private val texInline = Regex("""\$([^$]{1,400})\$""")
    private val texFont = Regex("""\\(mathrm|text|textrm|mathit|mathbf|rm|mathcal|boldsymbol|operatorname)\s*\{([^{}]*)\}""")
    private val textFont = Regex("""\\(texttt|textit|textbf|textrm|textsc|emph|mathrm|mathit|mathbf)\s*\{([^{}$]*)\}""")
    private val texCommand = Regex("""\\([A-Za-z]+)""")
    private val texSupGroup = Regex("""\^\{([^{}]*)\}""")
    private val texSupChar = Regex("""\^(.)""")
    private val texSubGroup = Regex("""_\{([^{}]*)\}""")
    private val texSubChar = Regex("""_(.)""")

    private fun script(body: String, table: Map<Char, Char>, mark: String): String = when {
        body.all { it in table } -> body.map { table.getValue(it) }.joinToString("")
        body.length == 1 -> "$mark$body"
        else -> "$mark($body)"
    }

    /** Turns simple inline TeX such as $^{208}\mathrm{Pb}$ or $\alpha$ into readable Unicode. */
    fun latexToUnicode(text: String): String {
        // Font commands also appear outside math mode, e.g. \texttt{TAURUS}.
        val plain = textFont.replace(text) { it.groupValues[2] }
        if (!plain.contains('$')) return plain
        return texInline.replace(plain) { m ->
            var t = m.groupValues[1]
            t = texFont.replace(t) { it.groupValues[2] }
            t = texCommand.replace(t) { texSymbols[it.groupValues[1]] ?: it.groupValues[1] }
            t = texSupGroup.replace(t) { script(it.groupValues[1], superscripts, "^") }
            t = texSupChar.replace(t) { script(it.groupValues[1], superscripts, "^") }
            t = texSubGroup.replace(t) { script(it.groupValues[1], subscripts, "_") }
            t = texSubChar.replace(t) { script(it.groupValues[1], subscripts, "_") }
            t.replace("{", "").replace("}", "").replace("\\,", " ").replace("~", " ")
        }
    }

    fun cleanTitle(raw: String?): String = clean(raw)?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim() ?: ""

    fun splitAuthors(raw: String): List<String> =
        raw.replace(Regex("\\s+"), " ")
            .split(Regex(",\\s*and\\s+|,\\s*|\\s+and\\s+|;\\s*|\\s+&\\s+"))
            .map { it.trim() }
            .filter { it.length > 1 && !it.equals("et al", true) }

    /** Lower-case, accent-free form used for comparing names. */
    fun foldName(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun familyName(name: String): String = foldName(name).split(' ').lastOrNull().orEmpty()

    private val rfc822 = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm zzz", Locale.ENGLISH),
    )

    fun parseDate(raw: String?): Long? {
        val s = raw?.trim()?.ifBlank { null } ?: return null
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return ZonedDateTime.parse(s).toInstant().toEpochMilli() }
        for (f in rfc822) runCatching { return ZonedDateTime.parse(s, f).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(s.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
        return null
    }

    fun yearOf(epochMillis: Long): Int =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).year
}
