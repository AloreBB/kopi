package dev.alorebb.kopi.core

// Clipboard preview text and card-number detection (see Global Constraints).
sealed interface PreviewInput {
    data class Text(val text: String, val sensitive: Boolean) : PreviewInput
    data object Image : PreviewInput
}

// Returns true when s holds only ASCII digits (after removing whitespace and
// hyphens), is 13-19 digits long, and passes the Luhn checksum.
fun isCardNumber(s: String): Boolean {
    val digits = cardDigits(s)
    if (digits.length !in 13..19 || !digits.all { it in '0'..'9' }) return false
    var sum = 0
    digits.reversed().forEachIndexed { index, char ->
        var digit = char - '0'
        if (index % 2 == 1) {
            digit *= 2
            if (digit > 9) digit -= 9
        }
        sum += digit
    }
    return sum % 10 == 0
}

// Card masking wins over the sensitive flag; images show a fixed label;
// plain text is whitespace-collapsed and cut at 40 code points plus "…"
// (code points, not UTF-16 chars, so emoji are never split in half).
fun preview(input: PreviewInput): String = when (input) {
    PreviewInput.Image -> "imagen"
    is PreviewInput.Text -> when {
        isCardNumber(input.text) -> "tarjeta •••• ${cardDigits(input.text).takeLast(4)}"
        input.sensitive -> "contenido oculto"
        else -> truncate(collapseWhitespace(input.text))
    }
}

private fun cardDigits(s: String): String = s.filter { !it.isWhitespace() && it != '-' }

private fun collapseWhitespace(s: String): String =
    s.splitWhere { it.isWhitespace() }.filter { it.isNotEmpty() }.joinToString(" ")

private fun String.splitWhere(isSeparator: (Char) -> Boolean): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    forEachIndexed { index, char ->
        if (isSeparator(char)) {
            parts += substring(start, index)
            start = index + 1
        }
    }
    parts += substring(start)
    return parts
}

private fun truncate(s: String): String {
    val max = Limits.PREVIEW_MAX_CHARACTERS
    if (s.codePointCount(0, s.length) <= max) return s
    return s.substring(0, s.offsetByCodePoints(0, max)) + "…"
}
