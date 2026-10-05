package com.nixikon.tgwsproxy

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Helpers for building the screens in code.
 *
 * The desktop UI is a scrollable list of titled sections, labelled fields and
 * checkboxes; these helpers reproduce that structure without XML layouts.
 *
 * Buttons are laid out on a fixed-height grid: a row of equally weighted
 * buttons all get the same height and centre up to two lines of text, so labels
 * such as "Открыть в Telegram" wrap tidily instead of producing ragged rows.
 */
object UiKit {

    /** Height of a button, chosen so two lines of 13sp text fit comfortably. */
    private const val BUTTON_HEIGHT_DP = 56f

    fun dp(ctx: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics
        ).toInt()

    fun themeColor(ctx: Context, attr: Int, fallback: Int = 0xFF888888.toInt()): Int {
        val tv = TypedValue()
        if (!ctx.theme.resolveAttribute(attr, tv, true)) return fallback
        return if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
    }

    private fun colorOnSurface(ctx: Context) =
        themeColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt())

    private fun colorOnSurfaceVariant(ctx: Context) =
        themeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt())

    private fun colorSurfaceVariant(ctx: Context) =
        themeColor(ctx, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFEEEEEE.toInt())

    private fun colorOutline(ctx: Context) =
        themeColor(ctx, com.google.android.material.R.attr.colorOutline, 0xFFCCCCCC.toInt())

    fun matchWrap(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = top
            bottomMargin = bottom
        }

    fun label(
        ctx: Context,
        text: String,
        sizeSp: Float = 14f,
        bold: Boolean = false,
        secondary: Boolean = false,
    ): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(if (secondary) colorOnSurfaceVariant(ctx) else colorOnSurface(ctx))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
    }

    /** Adds a titled section and returns the container to put rows into. */
    fun addSection(parent: LinearLayout, ctx: Context, title: String): LinearLayout {
        parent.addView(
            label(ctx, title, 15f, bold = true),
            matchWrap(top = dp(ctx, 12f), bottom = dp(ctx, 6f)),
        )
        val card = MaterialCardView(ctx).apply {
            radius = dp(ctx, 10f).toFloat()
            strokeWidth = dp(ctx, 1f)
            setCardBackgroundColor(colorSurfaceVariant(ctx))
            strokeColor = colorOutline(ctx)
            layoutParams = matchWrap(bottom = dp(ctx, 4f))
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12f), dp(ctx, 8f), dp(ctx, 12f), dp(ctx, 12f))
        }
        card.addView(
            body,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        parent.addView(card)
        return body
    }

    /** Labelled text field; [hint] becomes the supporting text (desktop tooltip). */
    fun addField(
        body: LinearLayout,
        ctx: Context,
        labelText: String,
        value: String,
        hint: String? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        multiline: Boolean = false,
        monospace: Boolean = false,
    ): EditText {
        val layout = TextInputLayout(
            ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            this.hint = labelText
            if (!hint.isNullOrEmpty()) helperText = hint
            layoutParams = matchWrap(top = dp(ctx, 6f))
        }
        val field = TextInputEditText(layout.context).apply {
            setText(value)
            this.inputType = inputType
            if (multiline) {
                setSingleLine(false)
                minLines = 4
                gravity = Gravity.TOP or Gravity.START
            }
            if (monospace) setTypeface(Typeface.MONOSPACE)
        }
        layout.addView(field)
        body.addView(layout)
        return field
    }

    /** Checkbox row (label + switch), mirroring the desktop `_checkbox` helper. */
    fun addSwitch(
        body: LinearLayout,
        ctx: Context,
        labelText: String,
        checked: Boolean,
        hint: String? = null,
    ): SwitchCompat {
        val switch = SwitchCompat(ctx).apply {
            isChecked = checked
            contentDescription = labelText
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = matchWrap(top = dp(ctx, 8f))
            // The whole row toggles: hitting a 40 dp thumb is fussy on a phone.
            isClickable = true
            isFocusable = true
            setOnClickListener { switch.toggle() }
        }
        row.addView(
            label(ctx, labelText, 14f),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(switch)
        body.addView(row)

        if (!hint.isNullOrEmpty()) {
            body.addView(
                label(ctx, hint, 12f, secondary = true),
                matchWrap(bottom = dp(ctx, 2f)),
            )
        }
        return switch
    }

    fun addSpinner(
        body: LinearLayout,
        ctx: Context,
        labelText: String,
        values: List<String>,
        selectedIndex: Int,
    ): Spinner {
        body.addView(
            label(ctx, labelText, 13f, secondary = true),
            matchWrap(top = dp(ctx, 8f), bottom = dp(ctx, 2f)),
        )
        val spinner = Spinner(ctx)
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, values)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        if (selectedIndex in values.indices) spinner.setSelection(selectedIndex)
        body.addView(spinner, matchWrap())
        return spinner
    }

    fun button(ctx: Context, text: String, primary: Boolean = false): MaterialButton =
        MaterialButton(ctx).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            gravity = Gravity.CENTER
            // MaterialButton's default insets eat into the label width, which is
            // what made longer labels wrap badly. The horizontal padding is
            // trimmed for the same reason: at 16 dp per side a two-column row on
            // a 360 dp screen leaves so little room that a long word such as
            // "Оригинальный" is broken in the middle instead of at the space.
            insetTop = 0
            insetBottom = 0
            setPadding(dp(ctx, 8f), paddingTop, dp(ctx, 8f), paddingBottom)
            minHeight = 0
            minimumHeight = dp(ctx, BUTTON_HEIGHT_DP)
            if (!primary) {
                backgroundTintList = ColorStateList.valueOf(colorSurfaceVariant(ctx))
                setTextColor(colorOnSurface(ctx))
            }
        }

    /** A full-width button. */
    fun fullButton(
        body: LinearLayout,
        ctx: Context,
        text: String,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): MaterialButton {
        val btn = button(ctx, text, primary)
        btn.setOnClickListener { onClick() }
        body.addView(btn, matchWrap(top = dp(ctx, 8f)))
        return btn
    }

    /**
     * A row of equally weighted buttons on a fixed-height grid. Keep to two per
     * row: three long labels do not fit on a phone.
     */
    fun buttonRow(
        body: LinearLayout,
        ctx: Context,
        specs: List<Triple<String, Boolean, () -> Unit>>,
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = matchWrap(top = dp(ctx, 8f))
        }
        val height = dp(ctx, BUTTON_HEIGHT_DP)
        specs.forEachIndexed { index, (text, primary, onClick) ->
            val btn = button(ctx, text, primary)
            btn.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(0, height, 1f)
            if (index > 0) lp.marginStart = dp(ctx, 8f)
            row.addView(btn, lp)
        }
        body.addView(row)
        return row
    }

    /** Compact inline button, sized to its label. */
    fun compactButton(
        body: LinearLayout,
        ctx: Context,
        text: String,
        onClick: () -> Unit,
    ): MaterialButton {
        val btn = button(ctx, text)
        btn.setOnClickListener { onClick() }
        btn.maxLines = 1
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 44f)
        ).apply { topMargin = dp(ctx, 8f) }
        body.addView(btn, lp)
        return btn
    }

    fun addDivider(body: LinearLayout, ctx: Context) {
        body.addView(View(ctx).apply {
            setBackgroundColor(colorOutline(ctx))
            layoutParams = matchWrap(top = dp(ctx, 10f), bottom = dp(ctx, 6f)).also {
                it.height = dp(ctx, 1f)
            }
        })
    }

    fun spacer(body: LinearLayout, ctx: Context, heightDp: Float = 12f) {
        body.addView(
            View(ctx),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)
            ),
        )
    }

    /**
     * Shows or hides a whole section, including its heading. The heading was
     * added to the same parent immediately before the card, so it is found by
     * index rather than by keeping a second reference around.
     */
    fun setSectionVisible(body: LinearLayout, visible: Boolean) {
        val card = body.parent as? View ?: return
        card.visibility = if (visible) View.VISIBLE else View.GONE
        val parent = card.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(card)
        if (index > 0) {
            parent.getChildAt(index - 1).visibility =
                if (visible) View.VISIBLE else View.GONE
        }
    }

    fun onTextChanged(field: EditText, action: () -> Unit) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = action()
        })
    }

    fun toast(ctx: Context, message: String) {
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
    }
}
