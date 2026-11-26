package com.anpaiss.notifybridge.phone

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView

/** The extensions' look, as in their banners: dark page, white text, grey for the rest, NotifyBridge's orange; one font. */
object Ui {
    const val BG = 0xFF0F1115.toInt()
    const val PANEL = 0xFF1C1F26.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val MUTED = 0xFFC9CCD3.toInt()
    const val ACCENT = 0xFFFF5823.toInt()
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(c: Context, color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = c.dp(10).toFloat() }

    fun text(c: Context, s: String = "", size: Float = 17f, color: Int = TEXT) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color); typeface = REGULAR
    }

    /** A small orange heading in spaced capitals, as "FOR HAMMERHEAD KAROO" in the banner. */
    fun heading(c: Context, s: String) = text(c, s.uppercase(), 14f, ACCENT).apply {
        typeface = MEDIUM; letterSpacing = 0.12f; setPadding(0, c.dp(20), 0, c.dp(6))
    }

    fun panel(c: Context, s: String) = text(c, s, 15f, MUTED).apply {
        val p = c.dp(12); setPadding(p, p, p, p); background = rounded(c, PANEL)
    }

    /** Orange for the main action, dark grey for the others. */
    fun button(c: Context, label: String, primary: Boolean = false) = Button(c).apply {
        text = label; isAllCaps = false; textSize = 17f; typeface = MEDIUM; setTextColor(TEXT)
        background = rounded(c, if (primary) ACCENT else PANEL); stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = c.dp(8) }
    }

    /** Radio buttons and check boxes in the same font and colours as the rest. */
    fun <T : CompoundButton> T.styled(size: Float = 17f): T = apply {
        textSize = size; typeface = REGULAR; setTextColor(TEXT)
        buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ACCENT, MUTED))
    }
}
