package com.kiaashkan.editnote

import android.graphics.Color

/**
 * Editor colour schemes ("Visual Styles" in the editor menu, modelled on
 * the well-known highlight.js themes). A style recolours ONLY the editing
 * area — background, text, line-number gutter and the syntax token
 * colours — and is independent of the app theme/accent. "Default" is
 * special-cased in EditorActivity to follow the app theme.
 */
data class EditorStyle(
    val id: String,
    val name: String,
    val background: Int,
    val foreground: Int,
    val keyword: Int,
    val string: Int,
    val comment: Int,
    val number: Int,
    val tag: Int,
    val property: Int
) {
    /** Slightly offset from the background, for the line-number gutter. */
    val gutterBackground: Int
        get() = shift(background, if (isDark()) 14 else -8)

    val gutterText: Int get() = comment

    /** Selection highlight: the property colour, translucent. */
    val selection: Int
        get() = Color.argb(
            70, Color.red(property), Color.green(property), Color.blue(property)
        )

    fun isDark(): Boolean {
        val lum = 0.299 * Color.red(background) +
            0.587 * Color.green(background) +
            0.114 * Color.blue(background)
        return lum < 128
    }

    private fun shift(color: Int, delta: Int): Int {
        fun clamp(v: Int) = v.coerceIn(0, 255)
        return Color.rgb(
            clamp(Color.red(color) + delta),
            clamp(Color.green(color) + delta),
            clamp(Color.blue(color) + delta)
        )
    }
}

object EditorStyles {

    const val DEFAULT_ID = "default"

    private fun hex(s: String): Int = Color.parseColor(s)

    private fun style(
        id: String, name: String, bg: String, fg: String,
        keyword: String, string: String, comment: String,
        number: String, tag: String, property: String
    ) = EditorStyle(
        id, name, hex(bg), hex(fg), hex(keyword), hex(string),
        hex(comment), hex(number), hex(tag), hex(property)
    )

    val ALL: List<EditorStyle> = listOf(
        style(
            "github", "GitHub", "#FFFFFF", "#24292E",
            "#D73A49", "#032F62", "#6A737D", "#005CC5", "#22863A", "#6F42C1"
        ),
        style(
            "github_dark", "GitHub Dark", "#0D1117", "#C9D1D9",
            "#FF7B72", "#A5D6FF", "#8B949E", "#79C0FF", "#7EE787", "#D2A8FF"
        ),
        style(
            "one_light", "One Light", "#FAFAFA", "#383A42",
            "#A626A4", "#50A14F", "#A0A1A7", "#986801", "#E45649", "#4078F2"
        ),
        style(
            "one_dark", "One Dark", "#282C34", "#ABB2BF",
            "#C678DD", "#98C379", "#5C6370", "#D19A66", "#E06C75", "#61AFEF"
        ),
        style(
            "intellij_light", "IntelliJ Light", "#FFFFFF", "#000000",
            "#0033B3", "#067D17", "#8C8C8C", "#1750EB", "#0033B3", "#871094"
        ),
        style(
            "idea", "IDEA", "#FFFFFF", "#000000",
            "#000080", "#008000", "#808080", "#0000FF", "#000080", "#660E7A"
        ),
        style(
            "gruvbox", "Gruvbox", "#282828", "#EBDBB2",
            "#FB4934", "#B8BB26", "#928374", "#D3869B", "#FABD2F", "#83A598"
        ),
        style(
            "tokyo_night", "Tokyo Night", "#1A1B26", "#A9B1D6",
            "#BB9AF7", "#9ECE6A", "#565F89", "#FF9E64", "#F7768E", "#7AA2F7"
        ),
        style(
            "tomorrow", "Tomorrow", "#FFFFFF", "#4D4D4C",
            "#8959A8", "#718C00", "#8E908C", "#F5871F", "#C82829", "#4271AE"
        ),
        style(
            "solarized_light", "Solarized Light", "#FDF6E3", "#657B83",
            "#859900", "#2AA198", "#93A1A1", "#D33682", "#268BD2", "#B58900"
        ),
        style(
            "solarized_dark", "Solarized Dark", "#002B36", "#839496",
            "#859900", "#2AA198", "#586E75", "#D33682", "#268BD2", "#B58900"
        ),
        style(
            "monokai", "Monokai", "#272822", "#F8F8F2",
            "#F92672", "#E6DB74", "#75715E", "#AE81FF", "#F92672", "#66D9EF"
        ),
        style(
            "dracula", "Dracula", "#282A36", "#F8F8F2",
            "#FF79C6", "#F1FA8C", "#6272A4", "#BD93F9", "#FF79C6", "#50FA7B"
        ),
        style(
            "xcode", "XCode", "#FFFFFF", "#000000",
            "#AD3DA4", "#D12F1B", "#5D6C79", "#272AD8", "#AD3DA4", "#703DAA"
        ),
        style(
            "rose_pine_dawn", "Rose Pine Dawn", "#FAF4ED", "#575279",
            "#286983", "#EA9D34", "#9893A5", "#D7827E", "#B4637A", "#907AA9"
        ),
        style(
            "panda_light", "Panda Syntax Light", "#FFFFFF", "#292A2B",
            "#A23982", "#2C8A2E", "#AAAAAA", "#7641BD", "#D73A49", "#2072B5"
        )
    )

    fun byId(id: String): EditorStyle? = ALL.firstOrNull { it.id == id }

    /** Menu labels, Default first (follows the app theme). */
    fun menuNames(): List<String> = listOf("Default") + ALL.map { it.name }
}
