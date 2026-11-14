package com.kiaashkan.editnote

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.kiaashkan.editnote.data.AppSettings

/** Runtime colors that follow the user's theme choice (incl. forced dark). */
class Palette(context: Context) {
    private val dark = AppSettings(context).isDark(context)
    val accent: Int = AppSettings(context).accentColor(context)

    /** Readable text color on top of the accent, by luminance: light
     *  accents (white mono, KiA's Pink #FFCCFF and Cream #FFFFCC) get
     *  dark text; everything else gets white. */
    val onAccent: Int = run {
        val lum = (0.299 * Color.red(accent) + 0.587 * Color.green(accent) +
            0.114 * Color.blue(accent)) / 255.0
        if (lum > 0.6) Color.rgb(17, 17, 17) else Color.WHITE
    }

    // Light mode (KiA, item 72): the old trio — background 250,
    // card white, border 226 — sat within a few levels of each
    // other, so cards were invisible against the background and
    // the whole app looked washed out. The background is now a
    // real light grey (matching the theme's windowBackground),
    // alt is a step deeper, and the border is a clearly visible
    // hairline. Dark values are unchanged.
    val surface: Int = if (dark) Color.rgb(17, 17, 17) else Color.rgb(241, 241, 244)
    val card: Int = if (dark) Color.rgb(30, 30, 32) else Color.WHITE
    val alt: Int = if (dark) Color.rgb(38, 38, 40) else Color.rgb(231, 231, 236)
    val border: Int = if (dark) Color.rgb(52, 52, 54) else Color.rgb(196, 196, 204)
    val textPrimary: Int = if (dark) Color.rgb(238, 238, 238) else Color.rgb(28, 27, 31)
    val textSecondary: Int = if (dark) Color.rgb(158, 158, 160) else Color.rgb(110, 110, 115)

    fun cardDrawable(radiusDp: Float = 16f, density: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(card)
            setStroke((1 * density).toInt().coerceAtLeast(1), border)
        }

    fun pillDrawable(density: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(alt)
            // Item 72: the search pill floated borderless on the
            // background; give it the same hairline as the cards.
            setStroke((1 * density).toInt().coerceAtLeast(1), border)
        }

    fun ovalDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    fun chipDrawable(selected: Boolean, density: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(if (selected) accent else alt)
            if (!selected) {
                // A hairline outline keeps inactive tabs crisp on the
                // header background (KiA, item 22 tab polish; alpha
                // raised 70 → 110 in item 72 so the outline actually
                // reads in light mode).
                setStroke(
                    (1 * density).toInt(),
                    Color.argb(
                        110, Color.red(textSecondary),
                        Color.green(textSecondary), Color.blue(textSecondary)
                    )
                )
            }
        }
}
