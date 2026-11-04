package app.honmani.reader.data.settings

/**
 * The reader's own CSS for book text (`reader-style.js` scopes it to the book and checks it again
 * in the page). The app shows a problem here, while typing, instead of leaving the page to ignore
 * the text without a word.
 */
object CustomCss {
    /** The most UTF-8 bytes stored; `MAX_CUSTOM_CSS_BYTES` in `reader-style.js` is the same number. */
    const val MAX_BYTES = 20 * 1024

    enum class Problem { TOO_LONG, UNBALANCED }

    fun bytes(css: String): Int = css.toByteArray(Charsets.UTF_8).size

    /** Whether a stored value (a backup's too) is acceptable: within the cap, no NUL. */
    fun fits(css: String): Boolean = bytes(css) <= MAX_BYTES && '\u0000' !in css

    /**
     * Why the page would not use [css], or null. Braces and parentheses that do not match could end
     * the book's rule early, so the page drops the whole text; strings and comments are skipped.
     */
    fun problem(css: String): Problem? {
        if (bytes(css) > MAX_BYTES) return Problem.TOO_LONG
        var depth = 0
        var parens = 0
        var i = 0
        while (i < css.length) {
            val c = css[i]
            when {
                c == '/' && css.startsWith("/*", i) -> {
                    val end = css.indexOf("*/", i + 2)
                    if (end < 0) return Problem.UNBALANCED
                    i = end + 1
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < css.length && css[j] != c && css[j] != '\n') j += if (css[j] == '\\') 2 else 1
                    if (j >= css.length || css[j] != c) return Problem.UNBALANCED
                    i = j
                }
                c == '\\' -> i++
                c == '{' -> depth++
                c == '}' -> if (--depth < 0) return Problem.UNBALANCED
                c == '(' -> parens++
                c == ')' -> if (--parens < 0) return Problem.UNBALANCED
            }
            i++
        }
        return if (depth == 0 && parens == 0) null else Problem.UNBALANCED
    }
}
