package com.anpaiss.notifybridge.karoo

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.anpaiss.notifybridge.karoo.popupturn.PopupTurn

/**
 * The message as an overlay at the bottom of the ride screen, in large text and
 * in the colour of the app it comes from. The Karoo's own InRideAlert cannot be
 * moved or resized, and other overlays (AxsRatio) can cover it at the top.
 * Same overlay technique as PoiAhead and BetterETA. A tap closes it, or 10 s.
 *
 * One popup at a time with the other extensions ([PopupTurn]): a message waits
 * while another popup is on screen, 30 s at most, then it is left to the
 * notification log; the alarm waits as long as needed.
 */
class MessagePopup(private val context: Context) {

    companion object {
        private const val AUTO_DISMISS_MS = 10_000L
        private const val MESSAGE_MAX_WAIT_MS = 30_000L
        private const val LIST_DISMISS_MS = 30_000L
        // The alarm keeps the screen to itself for 1 min, then the other popups may show over it (it stays
        // under them until a tap, siren included): its siren cannot go unheard, the MTA must not wait 10 min (14/11)
        private const val ALARM_HOLD_MS = 60_000L

        // Text size of the message popup, chosen in the app: message and app name in sp (20/11/2026)
        const val TEXT_SIZE = "text_size"
        val TEXT_SIZES = linkedMapOf("Small" to (20f to 14f), "Medium" to (24f to 16f), "Large" to (30f to 18f))
        const val DEFAULT_TEXT_SIZE = "Medium"
    }

    private fun sizes(): Pair<Float, Float> =
        TEXT_SIZES[context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getString(TEXT_SIZE, DEFAULT_TEXT_SIZE)]
            ?: TEXT_SIZES.getValue(DEFAULT_TEXT_SIZE)

    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(WindowManager::class.java)
    private var current: View? = null
    private val messageTurn = PopupTurn(context, "notifybridge.message") { FileLog.i(it) }
    private val alarmTurn = PopupTurn(context, "notifybridge.alarm") { FileLog.i(it) }

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** Callable from any thread. */
    fun show(message: String, app: String, color: Int) = messageTurn.request(PopupTurn.PRIORITY_MESSAGE, AUTO_DISMISS_MS,
        maxWaitMs = MESSAGE_MAX_WAIT_MS) {
        removeView()
        val (bigSp, smallSp) = sizes()
        val big = TextView(context).apply {
            text = message
            textSize = bigSp
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END   // a longer message ends in "…" instead of mid-sentence
            setShadowLayer(dp(2f), 0f, dp(1f), 0xB3000000.toInt())
        }
        val small = TextView(context).apply {
            text = app
            textSize = smallSp
            setTextColor(0xFFFFFFFF.toInt())
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12f).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(context.getColor(color)) }
            addView(big)
            addView(small)
            setOnClickListener { remove() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touches outside the popup go on to the ride screen
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(16f).toInt()
            horizontalMargin = 0.03f
        }
        runCatching { wm.addView(root, params) }.onSuccess {
            current = root
            main.postDelayed({ if (current === root) remove() }, AUTO_DISMISS_MS)
        }.onFailure { messageTurn.done() }
    }

    /**
     * The last messages, newest first, from a tap on the Messages field (21/11/2026): each in the colour
     * of its app, in the chosen text size. A tap closes it, or [LIST_DISMISS_MS]. Callable from any thread.
     */
    fun showList(entries: List<MessageLog.Entry>, color: (String) -> Int) = messageTurn.request(PopupTurn.PRIORITY_MESSAGE,
        LIST_DISMISS_MS, maxWaitMs = MESSAGE_MAX_WAIT_MS) {
        removeView()
        val (bigSp, smallSp) = sizes()
        val pad = dp(12f).toInt()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(0xF0202020.toInt()) }
            addView(TextView(context).apply {
                text = if (entries.isEmpty()) "No messages in this ride" else "MESSAGES (last ${entries.size})"
                textSize = smallSp; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            })
            entries.forEach { e ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = dp(8f).toInt()
                    setPadding(p, p, p, p)
                    background = GradientDrawable().apply { cornerRadius = dp(8f); setColor(context.getColor(color(e.app))) }
                    addView(TextView(context).apply {
                        text = "${MessagesDataType.hhmm(e.at)}  ${e.app}" + if (e.title.isNotEmpty()) "  ·  ${e.title}" else ""
                        textSize = smallSp; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    if (e.text.isNotEmpty()) addView(TextView(context).apply {
                        text = e.text
                        textSize = bigSp * 0.8f; setTextColor(0xFFFFFFFF.toInt())
                        maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    setOnClickListener { remove() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6f).toInt() })
            }
            setOnClickListener { remove() }
        }
        val root = android.widget.ScrollView(context).apply { addView(list) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            horizontalMargin = 0.03f
            verticalMargin = 0.03f
        }
        runCatching { wm.addView(root, params) }.onSuccess {
            current = root
            main.postDelayed({ if (current === root) remove() }, LIST_DISMISS_MS)
        }.onFailure { messageTurn.done() }
    }

    // The alarm is a view of its own: a message arriving meanwhile must not close it
    private var alarm: View? = null

    /** The whole screen red until a tap; then [onClose]. Callable from any thread. */
    fun alarm(title: String, detail: String, onClose: () -> Unit) = alarmTurn.request(PopupTurn.PRIORITY_INREACH_ALARM, ALARM_HOLD_MS) {
        alarm?.let { runCatching { wm.removeView(it) } }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = dp(16f).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFD50000.toInt())
            addView(TextView(context).apply {
                text = title; textSize = 34f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            })
            addView(TextView(context).apply {
                text = detail + "\n\nTap to close"; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt()); setPadding(0, pad, 0, 0)
            })
            setOnClickListener {
                if (alarm === this) { runCatching { wm.removeView(this) }; alarm = null; alarmTurn.done(); onClose() }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        )
        runCatching { wm.addView(root, params) }.onSuccess { alarm = root }.onFailure { alarmTurn.done() }
    }

    fun remove() {
        removeView()
        messageTurn.done()
    }

    private fun removeView() {
        current?.let { runCatching { wm.removeView(it) } }
        current = null
    }

    private fun dp(v: Float) = v * context.resources.displayMetrics.density
}
