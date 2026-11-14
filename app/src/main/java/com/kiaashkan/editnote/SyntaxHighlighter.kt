package com.kiaashkan.editnote

import android.text.Spannable
import android.text.style.ForegroundColorSpan

/**
 * Real per-language syntax highlighting. Each language has its own
 * keywords, comment styles and string rules; colours come from the
 * active [EditorStyle] (Visual Styles). The scanner honours the usual
 * precedence — comments and strings first, then numbers, then words —
 * so a keyword inside a comment or a string is never coloured.
 *
 * Deliberately conservative: capped document size, no spans on failure,
 * and plain text files are never touched.
 */
object SyntaxHighlighter {

    const val AUTO = "auto"
    const val NONE = "none"

    private const val MAX_LENGTH = 200_000

    class Lang(
        val id: String,
        val display: String,
        val extensions: Set<String>,
        val keywords: Set<String>,
        val builtins: Set<String> = emptySet(),
        val caseInsensitive: Boolean = false,
        val lineComments: List<String> = listOf("//"),
        val blockComment: Pair<String, String>? = "/*" to "*/",
        val quotes: List<String> = listOf("\"", "'"),
        val tripleQuotes: List<String> = emptyList(),
        val charLiteral: Boolean = true,
        val preprocessor: Boolean = false,
        val variables: Boolean = false
    )

    private fun kw(s: String): Set<String> = s.split(' ').filter { it.isNotBlank() }.toSet()

    private val C_KEYWORDS = kw(
        "auto break case char const continue default do double else enum extern " +
            "float for goto if int long register return short signed sizeof static " +
            "struct switch typedef union unsigned void volatile while inline restrict"
    )

    val LANGUAGES: List<Lang> = listOf(
        Lang(
            "kotlin", "Kotlin", setOf("kt", "kts"),
            kw(
                "package import fun val var class object interface if else when for while do return break continue " +
                    "public private protected internal open final abstract override data sealed enum annotation inner " +
                    "this super null true false is as in out vararg lateinit lazy suspend inline crossinline noinline " +
                    "try catch finally throw typealias companion init construct by delegate lazy get set where reified tailrec operator infix external expect actual"
            ),
            builtins = kw("println print listOf mapOf setOf arrayOf emptyList mutableListOf mutableMapOf TODO require checkNotNull"),
            quotes = listOf("\"")
        ),
        Lang(
            "java", "Java", setOf("java"),
            kw(
                "package import class interface enum record if else switch case default for while do return break continue " +
                    "public private protected static final abstract void int long float double boolean char byte short new " +
                    "this super null true false try catch finally throw throws instanceof synchronized volatile transient native strictfp var sealed permits"
            ),
            builtins = kw("String System Object Integer Long Double Boolean Math Override Deprecated SuppressWarnings"),
            quotes = listOf("\"")
        ),
        Lang(
            "python", "Python", setOf("py"),
            kw(
                "def class return if elif else for while try except finally with as import from pass break continue " +
                    "lambda and or not in is None True False global nonlocal yield async await raise assert del print"
            ),
            builtins = kw("len range str int float list dict set tuple bool type isinstance open enumerate zip map filter sum min max abs repr id input"),
            lineComments = listOf("#"), blockComment = null,
            quotes = listOf("\"", "'"), tripleQuotes = listOf("\"\"\"", "'''"),
            charLiteral = false
        ),
        Lang(
            "javascript", "JavaScript", setOf("js", "mjs", "jsx"),
            kw(
                "var let const function return if else switch case default for while do break continue new delete typeof " +
                    "instanceof in of class extends super this null undefined true false try catch finally throw async await yield static get set import export from"
            ),
            builtins = kw("console document window Math JSON Promise Array Object String Number Boolean Error setTimeout setInterval fetch require module"),
            quotes = listOf("\"", "'", "`")
        ),
        Lang(
            "typescript", "TypeScript", setOf("ts", "tsx"),
            kw(
                "var let const function return if else switch case default for while do break continue new delete typeof " +
                    "instanceof in of class extends super this null undefined true false try catch finally throw async await " +
                    "interface type enum namespace declare abstract readonly public private protected implements keyof infer any unknown never void string number boolean symbol object"
            ),
            builtins = kw("console document window Math JSON Promise Array Object String Number Boolean Error setTimeout setInterval fetch require"),
            quotes = listOf("\"", "'", "`")
        ),
        Lang(
            "c", "C", setOf("c", "h"),
            C_KEYWORDS,
            builtins = kw("printf scanf malloc free calloc realloc sizeof NULL size_t FILE stdin stdout stderr memcpy memset strlen strcpy"),
            quotes = listOf("\""), preprocessor = true
        ),
        Lang(
            "cpp", "C++", setOf("cpp", "cxx", "cc", "hpp", "hh", "hxx"),
            C_KEYWORDS + kw(
                "bool class template typename namespace using virtual friend this new delete public private protected " +
                    "true false nullptr constexpr consteval constinit mutable noexcept override final concept requires static_cast dynamic_cast reinterpret_cast const_cast"
            ),
            builtins = kw("std cout cin endl string vector map set unique_ptr shared_ptr make_unique make_shared NULL size_t"),
            quotes = listOf("\""), preprocessor = true
        ),
        Lang(
            "csharp", "C#", setOf("cs"),
            kw(
                "namespace using class interface struct enum record if else switch case default for foreach while do return break continue " +
                    "public private protected internal static readonly const void int long float double bool char string var new this base null true false " +
                    "try catch finally throw async await out ref in params sealed abstract virtual override event delegate operator explicit implicit get set add remove where"
            ),
            builtins = kw("Console String Object Int32 Boolean Task List Dictionary WriteLine ToString Exception Override"),
            quotes = listOf("\"", "'")
        ),
        Lang(
            "go", "Go", setOf("go"),
            kw(
                "package import func var const type struct interface map chan if else switch case default for range go defer " +
                    "return break continue select fallthrough true false nil iota"
            ),
            builtins = kw("string int int64 float64 bool byte rune error make new len cap append copy delete close panic recover print println"),
            quotes = listOf("\"", "`")
        ),
        Lang(
            "rust", "Rust", setOf("rs"),
            kw(
                "fn let mut const static ref move type struct enum trait impl for in loop while if else match return break continue " +
                    "pub crate mod use super self Self where async await dyn box true false unsafe extern as"
            ),
            builtins = kw("String Vec Option Result Some None Ok Err Box println eprintln format vec i32 i64 u32 u64 usize f64 bool char str"),
            quotes = listOf("\"")
        ),
        Lang(
            "php", "PHP", setOf("php"),
            kw(
                "echo function return if else elseif endif for endforeach while do switch case default break continue class " +
                    "interface trait extends implements public private protected static abstract final const new try catch finally throw " +
                    "namespace use as global var true false null and or xor instanceof clone require include require_once include_once print"
            ),
            builtins = kw("isset empty unset count is_array is_string strlen strpos str_replace array_push json_encode json_decode sprintf var_dump"),
            lineComments = listOf("//", "#"),
            quotes = listOf("\"", "'"), variables = true
        ),
        Lang(
            "ruby", "Ruby", setOf("rb"),
            kw(
                "def end class module if elsif else unless case when for while until begin rescue ensure return yield self " +
                    "super nil true false and or not in then do next break redo retry require include extend attr_accessor attr_reader attr_writer lambda proc puts"
            ),
            builtins = kw("puts print gets to_s to_i to_f length size each map select new initialize String Array Hash"),
            lineComments = listOf("#"), blockComment = null,
            quotes = listOf("\"", "'"), charLiteral = false
        ),
        Lang(
            "swift", "Swift", setOf("swift"),
            kw(
                "import class struct enum protocol extension func var let if else switch case default for while do return break continue " +
                    "guard defer where as is in self Self super init deinit subscript associatedtype typealias indirect infix postfix prefix operator precedencegroup " +
                    "public private fileprivate internal open final override required convenience lazy mutating nonmutating static weak unowned optional override some any actor async await try catch throw throws rethrows true false nil"
            ),
            builtins = kw("String Int Double Bool Array Dictionary Set Optional print Swift Foundation"),
            quotes = listOf("\"")
        ),
        Lang(
            "html", "HTML", setOf("html", "htm"),
            emptySet(), blockComment = "<!--" to "-->", lineComments = emptyList(),
            quotes = listOf("\"", "'"), charLiteral = false
        ),
        Lang(
            "xml", "XML", setOf("xml", "svg", "xsd", "xsl", "plist"),
            emptySet(), blockComment = "<!--" to "-->", lineComments = emptyList(),
            quotes = listOf("\"", "'"), charLiteral = false
        ),
        Lang(
            "css", "CSS", setOf("css", "scss", "less"),
            kw(
                "important media keyframes font-face import charset supports and or not only screen print all"
            ),
            lineComments = emptyList(),
            quotes = listOf("\"", "'"), charLiteral = false
        ),
        Lang(
            "json", "JSON", setOf("json"),
            kw("true false null"), lineComments = emptyList(), blockComment = null,
            quotes = listOf("\""), charLiteral = false
        ),
        Lang(
            "sql", "SQL", setOf("sql"),
            kw(
                "SELECT FROM WHERE INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE DROP ALTER INDEX VIEW JOIN LEFT RIGHT INNER OUTER " +
                    "ON GROUP BY ORDER LIMIT OFFSET AS DISTINCT UNION ALL PRIMARY KEY NOT NULL DEFAULT CONSTRAINT FOREIGN REFERENCES " +
                    "BEGIN COMMIT ROLLBACK TRANSACTION GRANT REVOKE EXISTS BETWEEN LIKE IN IS AND OR CASE WHEN THEN ELSE END DECLARE CURSOR"
            ),
            caseInsensitive = true,
            lineComments = listOf("--"), blockComment = "/*" to "*/",
            quotes = listOf("'"), charLiteral = false
        ),
        Lang(
            "shell", "Shell", setOf("sh", "bash", "zsh"),
            kw(
                "if then else elif fi for in do done while until case esac function echo printf read exit return local " +
                    "export source set unset shift test true false cd pwd eval exec trap declare typeset"
            ),
            builtins = kw("HOME PATH USER PWD SHELL TERM EDITOR"),
            lineComments = listOf("#"), blockComment = null,
            quotes = listOf("\"", "'"), charLiteral = false, variables = true
        ),
        Lang(
            "markdown", "Markdown", setOf("md", "markdown"),
            emptySet(), lineComments = emptyList(), blockComment = null,
            quotes = emptyList(), charLiteral = false
        ),
        Lang(
            "yaml", "YAML", setOf("yaml", "yml"),
            kw("true false null yes no on off"),
            lineComments = listOf("#"), blockComment = null,
            quotes = listOf("\"", "'"), charLiteral = false
        ),
        Lang(
            "ini", "INI", setOf("ini", "properties", "cfg", "conf", "toml"),
            kw("true false"),
            lineComments = listOf("#", ";"), blockComment = null,
            quotes = listOf("\"", "'"), charLiteral = false
        )
    )

    private val BY_ID = LANGUAGES.associateBy { it.id }
    private val BY_EXT = mutableMapOf<String, Lang>().apply {
        LANGUAGES.forEach { lang -> lang.extensions.forEach { put(it, lang) } }
    }

    fun languageForExtension(fileName: String): Lang? =
        BY_EXT[fileName.substringAfterLast('.', "").lowercase()]

    fun languageById(id: String): Lang? = BY_ID[id]

    /** Resolve a doc's syntax choice ("auto"/"none"/language id) to a Lang. */
    fun resolve(choice: String, fileName: String): Lang? = when (choice) {
        NONE -> null
        AUTO -> languageForExtension(fileName)
        else -> BY_ID[choice] ?: languageForExtension(fileName)
    }

    /** One coloured range: [from, to) in [color]. */
    class Range(val from: Int, val to: Int, val color: Int)

    /**
     * Compute the highlight ranges for [source] WITHOUT touching any
     * Spannable — pure string work, safe (and intended) to run on a
     * background thread. KiA's hang (item 50): running the scan and
     * the span writes on the UI thread froze the app on a large code
     * file; now only the final batched span write is on the UI thread.
     */
    fun compute(source: String, lang: Lang, style: EditorStyle): List<Range> {
        if (source.isEmpty() || source.length > MAX_LENGTH) return emptyList()
        val out = ArrayList<Range>()
        when (lang.id) {
            "html", "xml" -> markMarkup(out, source, style)
            "markdown" -> markMarkdown(out, source, style)
            "json" -> markJson(out, source, style)
            "css" -> markCss(out, source, lang, style)
            "ini", "yaml" -> markConfig(out, source, lang, style)
            else -> scan(out, source, lang, style)
        }
        return out
    }

    /** Write precomputed [ranges] onto [text] in one batch. */
    fun applyRanges(text: Spannable, ranges: List<Range>) {
        clear(text)
        for (r in ranges) span(text, r.color, r.from, r.to)
    }

    fun apply(text: Spannable, lang: Lang, style: EditorStyle) {
        applyRanges(text, compute(text.toString(), lang, style))
    }

    fun clear(text: Spannable) {
        text.getSpans(0, text.length, ForegroundColorSpan::class.java)
            .forEach { text.removeSpan(it) }
    }

    private fun span(out: MutableList<Range>, color: Int, from: Int, to: Int) {
        if (to > from && from >= 0) out.add(Range(from, to, color))
    }

    private fun span(text: Spannable, color: Int, from: Int, to: Int) {
        if (to > from && from >= 0 && to <= text.length) {
            text.setSpan(
                ForegroundColorSpan(color), from, to,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun isWordChar(c: Char, lang: Lang): Boolean =
        c.isLetterOrDigit() || c == '_' || (lang.variables && c == '$')

    /** Generic scanner for code-like languages. */
    private fun scan(out: MutableList<Range>, src: String, lang: Lang, style: EditorStyle) {
        val n = src.length
        var i = 0
        var lineStart = true
        while (i < n) {
            val c = src[i]
            // Preprocessor (#include …) for C-family languages.
            if (lang.preprocessor && lineStart && c == '#') {
                var end = src.indexOf('\n', i).let { if (it < 0) n else it }
                span(out, style.property, i, end)
                i = end
                continue
            }
            // Block comment.
            val block = lang.blockComment
            if (block != null && src.startsWith(block.first, i)) {
                val close = src.indexOf(block.second, i + block.first.length)
                val end = if (close < 0) n else close + block.second.length
                span(out, style.comment, i, end)
                i = end
                lineStart = false
                continue
            }
            // Line comment.
            val lineComment = lang.lineComments.firstOrNull { src.startsWith(it, i) }
            if (lineComment != null) {
                var end = src.indexOf('\n', i).let { if (it < 0) n else it }
                span(out, style.comment, i, end)
                i = end
                continue
            }
            // Triple-quoted strings (Python).
            val triple = lang.tripleQuotes.firstOrNull { src.startsWith(it, i) }
            if (triple != null) {
                val close = src.indexOf(triple, i + triple.length)
                val end = if (close < 0) n else close + triple.length
                span(out, style.string, i, end)
                i = end
                lineStart = false
                continue
            }
            // Quoted strings / chars.
            if (c.toString() in lang.quotes) {
                var j = i + 1
                var closed = false
                while (j < n) {
                    val cj = src[j]
                    if (cj == '\\') {
                        j += 2
                        continue
                    }
                    if (cj == c) {
                        closed = true
                        break
                    }
                    if (cj == '\n' && c != '`') break
                    // Char literal heuristic: 'x' is short; a longer run
                    // without a closing quote is an apostrophe, not a char.
                    if (c == '\'' && lang.charLiteral && j - i > 4 && lang.id != "sql") break
                    j++
                }
                if (closed) {
                    span(out, style.string, i, j + 1)
                    i = j + 1
                    lineStart = false
                    continue
                }
                i++
                lineStart = false
                continue
            }
            // Variables ($name) for PHP/Shell.
            if (lang.variables && c == '$' && i + 1 < n &&
                (src[i + 1].isLetter() || src[i + 1] == '_')
            ) {
                var j = i + 1
                while (j < n && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                span(out, style.property, i, j)
                i = j
                lineStart = false
                continue
            }
            // Numbers.
            if (c.isDigit() && (i == 0 || !isWordChar(src[i - 1], lang))) {
                var j = i
                while (j < n && (src[j].isLetterOrDigit() || src[j] == '.' || src[j] == '_')) j++
                span(out, style.number, i, j)
                i = j
                lineStart = false
                continue
            }
            // Words: keywords / builtins.
            if (c.isLetter() || c == '_' || c == '$') {
                var j = i
                while (j < n && isWordChar(src[j], lang)) j++
                val word = src.substring(i, j)
                val probe = if (lang.caseInsensitive) word.uppercase() else word
                when {
                    probe in lang.keywords -> span(out, style.keyword, i, j)
                    word in lang.builtins -> span(out, style.property, i, j)
                }
                i = j
                lineStart = false
                continue
            }
            if (c == '\n') lineStart = true else if (!c.isWhitespace()) lineStart = false
            i++
        }
    }

    /** HTML/XML: tags, attribute names, attribute values, comments. */
    private fun markMarkup(out: MutableList<Range>, src: String, style: EditorStyle) {
        val n = src.length
        var i = 0
        while (i < n) {
            if (src.startsWith("<!--", i)) {
                val close = src.indexOf("-->", i + 4)
                val end = if (close < 0) n else close + 3
                span(out, style.comment, i, end)
                i = end
                continue
            }
            if (src[i] == '<') {
                // One tag: colour the name, attribute names and values.
                var j = i + 1
                if (j < n && src[j] == '/') j++
                val nameStart = j
                while (j < n && (src[j].isLetterOrDigit() || src[j] in "-_:.")) j++
                if (j > nameStart) span(out, style.tag, nameStart, j)
                while (j < n && src[j] != '>') {
                    val cj = src[j]
                    if (cj == '"' || cj == '\'') {
                        var k = j + 1
                        while (k < n && src[k] != cj) k++
                        span(out, style.string, j, minOf(k + 1, n))
                        j = minOf(k + 1, n)
                        continue
                    }
                    if (cj.isLetter() || cj == '_' || cj == ':') {
                        var k = j
                        while (k < n && (src[k].isLetterOrDigit() || src[k] in "-_:.")) k++
                        span(out, style.property, j, k)
                        j = k
                        continue
                    }
                    j++
                }
                i = minOf(j + 1, n)
                continue
            }
            i++
        }
    }

    /** JSON: keys in property colour, strings, numbers, literals. */
    private fun markJson(out: MutableList<Range>, src: String, style: EditorStyle) {
        val stringRe = Regex("\"([^\"\\\\]|\\\\.)*\"")
        stringRe.findAll(src).forEach { m ->
            val after = src.substring(m.range.last + 1).trimStart()
            val color = if (after.startsWith(":")) style.property else style.string
            span(out, color, m.range.first, m.range.last + 1)
        }
        Regex("\\b(-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?)\\b").findAll(src).forEach {
            span(out, style.number, it.range.first, it.range.last + 1)
        }
        Regex("\\b(true|false|null)\\b").findAll(src).forEach {
            span(out, style.keyword, it.range.first, it.range.last + 1)
        }
    }

    /** CSS: comments/strings via the generic pass, properties + selectors. */
    private fun markCss(out: MutableList<Range>, src: String, lang: Lang, style: EditorStyle) {
        scan(out, src, lang, style)
        // Property names: word followed by ':' inside a rule body.
        Regex("([a-zA-Z-]+)\\s*:").findAll(src).forEach {
            val g = it.groups[1] ?: return@forEach
            span(out, style.property, g.range.first, g.range.last + 1)
        }
        // At-rules.
        Regex("@[a-zA-Z-]+").findAll(src).forEach {
            span(out, style.keyword, it.range.first, it.range.last + 1)
        }
    }

    /** INI/YAML: comments, section headers, keys, strings, numbers. */
    private fun markConfig(out: MutableList<Range>, src: String, lang: Lang, style: EditorStyle) {
        var pos = 0
        val n = src.length
        while (pos < n) {
            var lineEnd = src.indexOf('\n', pos).let { if (it < 0) n else it }
            val line = src.substring(pos, lineEnd)
            val trimmed = line.trimStart()
            val lead = line.length - trimmed.length
            when {
                trimmed.startsWith("#") || trimmed.startsWith(";") ->
                    span(out, style.comment, pos, lineEnd)
                trimmed.startsWith("[") && trimmed.contains("]") ->
                    span(out, style.tag, pos + lead, pos + lead + trimmed.indexOf(']') + 1)
                else -> {
                    val sep = if (lang.id == "yaml") trimmed.indexOf(':')
                    else minOf(
                        trimmed.indexOf('=').let { if (it < 0) Int.MAX_VALUE else it },
                        trimmed.indexOf(':').let { if (it < 0) Int.MAX_VALUE else it }
                    )
                    if (sep in 1..60) {
                        span(out, style.property, pos + lead, pos + lead + sep)
                        // Value side: strings, numbers, booleans.
                        val value = trimmed.substring(sep + 1)
                        val valueStart = pos + lead + sep + 1
                        Regex("\"[^\"]*\"|'[^']*'").findAll(value).forEach {
                            span(out, style.string, valueStart + it.range.first, valueStart + it.range.last + 1)
                        }
                        Regex("\\b(\\d+(\\.\\d+)?|true|false)\\b").findAll(value).forEach {
                            val color = if (it.value in setOf("true", "false")) style.keyword else style.number
                            span(out, color, valueStart + it.range.first, valueStart + it.range.last + 1)
                        }
                    }
                }
            }
            pos = lineEnd + 1
        }
    }

    /** Markdown: headings, code, links, emphasis markers. */
    private fun markMarkdown(out: MutableList<Range>, src: String, style: EditorStyle) {
        Regex("(?m)^#{1,6}\\s.*$").findAll(src).forEach {
            span(out, style.tag, it.range.first, it.range.last + 1)
        }
        Regex("`[^`\\n]+`").findAll(src).forEach {
            span(out, style.string, it.range.first, it.range.last + 1)
        }
        Regex("(?ms)^```.*?(^```|\\z)").findAll(src).forEach {
            span(out, style.string, it.range.first, it.range.last + 1)
        }
        Regex("\\[[^\\]]*\\]\\([^)]*\\)").findAll(src).forEach {
            span(out, style.property, it.range.first, it.range.last + 1)
        }
        Regex("(?m)^\\s{0,3}([-*+]|\\d+\\.)\\s").findAll(src).forEach {
            span(out, style.number, it.range.first, it.range.last + 1)
        }
    }
}
