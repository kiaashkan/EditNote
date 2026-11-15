package com.kiaashkan.editnote

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Every window in the app speaks one design language (KiA, item 47:
 * the remaining stock AlertDialogs — Save, Go to line, Find &
 * Replace, Statistics, the pickers — looked foreign next to the
 * app's own cards). These builders produce the same rounded card,
 * accent pill primary button and ripple text buttons used by the
 * Reset confirmation and the Settings pickers.
 */
object CardDialogs {

    class ButtonSpec(
        val label: String,
        val primary: Boolean = false,
        val onClick: (Dialog) -> Unit
    )

    private fun dp(activity: Activity, v: Int) =
        (v * activity.resources.displayMetrics.density).toInt()

    fun show(
        activity: Activity,
        palette: Palette,
        title: String,
        message: String? = null,
        content: ((LinearLayout) -> Unit)? = null,
        focusView: View? = null,
        buttons: List<ButtonSpec>
    ): Dialog {
        val dialog = Dialog(activity)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (message != null) {
                addView(TextView(activity).apply {
                    text = message
                    textSize = 14.5f
                    setTextColor(palette.textSecondary)
                    setPadding(0, dp(activity, 8), 0, 0)
                })
            }
        }
        content?.invoke(body)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.cardDrawable(20f, activity.resources.displayMetrics.density)
            setPadding(dp(activity, 22), dp(activity, 20), dp(activity, 22), dp(activity, 16))
            addView(TextView(activity).apply {
                text = title
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.textPrimary)
            })
            addView(body)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(activity, 18), 0, 0)
            }
            buttons.forEachIndexed { i, spec ->
                if (spec.primary) {
                    row.addView(Button(activity).apply {
                        text = spec.label
                        textSize = 15f
                        isAllCaps = false
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 24f * activity.resources.displayMetrics.density
                            setColor(palette.accent)
                        }
                        setTextColor(palette.onAccent)
                        setPadding(dp(activity, 20), 0, dp(activity, 20), 0)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, dp(activity, 42)
                        ).apply { if (i > 0) marginStart = dp(activity, 8) }
                        setOnClickListener { spec.onClick(dialog) }
                    })
                } else {
                    row.addView(TextView(activity).apply {
                        text = spec.label
                        textSize = 15f
                        setTextColor(palette.textPrimary)
                        val ripple = TypedValue()
                        activity.theme.resolveAttribute(
                            android.R.attr.selectableItemBackground, ripple, true
                        )
                        setBackgroundResource(ripple.resourceId)
                        setPadding(
                            dp(activity, 14), dp(activity, 10),
                            dp(activity, 14), dp(activity, 10)
                        )
                        if (i > 0) {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { marginStart = dp(activity, 4) }
                        }
                        setOnClickListener { spec.onClick(dialog) }
                    })
                }
            }
            addView(row)
        }
        dialog.setContentView(card)
        // Dialogs never mirror either (item 95).
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_LTR
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.9).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        if (focusView != null) {
            dialog.setOnShowListener {
                focusView.requestFocus()
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
                imm.showSoftInput(focusView, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
        return dialog
    }

    /** Styled text field for the card dialogs (accent outline, app colours). */
    fun inputField(
        activity: Activity,
        palette: Palette,
        hint: String,
        initial: String = "",
        inputType: Int = 0
    ): EditText = EditText(activity).apply {
        this.hint = hint
        setText(initial)
        if (initial.isNotEmpty()) setSelection(initial.length)
        if (inputType != 0) this.inputType = inputType
        textSize = 15.5f
        setTextColor(palette.textPrimary)
        setHintTextColor(palette.textSecondary)
        val density = activity.resources.displayMetrics.density
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(palette.card)
            setStroke((1.5f * density).toInt().coerceAtLeast(1), palette.accent)
        }
        val pad = (14 * density).toInt()
        setPadding(pad, (12 * density).toInt(), pad, (12 * density).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (14 * density).toInt() }
    }

    /** Single-choice card list (✓ on the current choice); with
     *  current = -1 it is a plain action list. Long lists scroll. */
    fun picker(
        activity: Activity,
        palette: Palette,
        title: String,
        options: List<String>,
        current: Int,
        onPick: (Int) -> Unit
    ) {
        val dialog = Dialog(activity)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        options.forEachIndexed { index, label ->
            list.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val ripple = TypedValue()
                activity.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, ripple, true
                )
                setBackgroundResource(ripple.resourceId)
                setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 12))
                addView(TextView(activity).apply {
                    text = label
                    textSize = 15.5f
                    setTextColor(palette.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                addView(TextView(activity).apply {
                    text = if (index == current) "✓" else ""
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.accent)
                })
                setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
            })
        }
        val scroll = ScrollView(activity).apply {
            addView(list)
            val cap = (activity.resources.displayMetrics.heightPixels * 0.62).toInt()
            post {
                if (height > cap) {
                    layoutParams = layoutParams.apply { height = cap }
                }
            }
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.cardDrawable(20f, activity.resources.displayMetrics.density)
            setPadding(0, dp(activity, 16), 0, dp(activity, 8))
            addView(TextView(activity).apply {
                text = title
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.textPrimary)
                setPadding(dp(activity, 18), 0, dp(activity, 18), dp(activity, 8))
            })
            addView(scroll)
        }
        dialog.setContentView(card)
        // Dialogs never mirror either (item 95).
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_LTR
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    fun confirm(
        activity: Activity,
        palette: Palette,
        title: String,
        message: String,
        positiveLabel: String,
        onPositive: () -> Unit,
        negativeLabel: String? = null,
        onNegative: (() -> Unit)? = null
    ) {
        val buttons = mutableListOf<ButtonSpec>()
        if (negativeLabel != null && onNegative != null) {
            buttons.add(ButtonSpec(negativeLabel) { d ->
                d.dismiss()
                onNegative()
            })
        }
        buttons.add(ButtonSpec("Cancel") { d -> d.dismiss() })
        buttons.add(ButtonSpec(positiveLabel, primary = true) { d ->
            d.dismiss()
            onPositive()
        })
        show(activity, palette, title, message, buttons = buttons)
    }

    fun info(
        activity: Activity,
        palette: Palette,
        title: String,
        message: String
    ) {
        show(
            activity, palette, title, message,
            buttons = listOf(ButtonSpec("OK", primary = true) { d -> d.dismiss() })
        )
    }
}
