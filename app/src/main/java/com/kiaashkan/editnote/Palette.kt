package com.kiaashkan.editnote

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.kiaashkan.editnote.data.AppSettings

/** Runtime colors that follow the user's theme choice (incl. forced dark). */
class Palette(context: Context) {
    private val dark = AppSettings(context).isDark(context)
    val accent: Int = AppSettings(context).accentColor(context)

    /** Readable text color on top of the accent (black on white mono, else white). */
    val onAccent: Int =
        if (accent == Color.WHITE) Color.rgb(17, 17, 17) else Color.WHITE

    val surface: Int = if (dark) Color.rgb(17, 17, 17) else Color.rgb(250, 250, 250)
    val card: Int = if (dark) Color.rgb(30, 30, 32) else Color.WHITE
    val alt: Int = if (dark) Color.rgb(38, 38, 40) else Color.rgb(240, 240, 242)
    val border: Int = if (dark) Color.rgb(52, 52, 54) else Color.rgb(226, 226, 228)
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
        }
}
