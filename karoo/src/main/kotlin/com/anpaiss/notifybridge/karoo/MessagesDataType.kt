package com.anpaiss.notifybridge.karoo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Messages field (21/11/2026): how many messages came in this ride and who
 * sent the last one, at what time. A tap opens the last [MessageLog.MAX]
 * ([MessagePopup.showList]); not in the profile editor preview.
 */
class MessagesDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    companion object {
        const val TYPE_ID = "messages"
        private val HHMM = SimpleDateFormat("HH:mm", Locale.ROOT)
        fun hhmm(t: Long): String = HHMM.format(Date(t))
    }

    private val main = Handler(Looper.getMainLooper())

    override fun startStream(emitter: Emitter<StreamState>) {
        val send = { emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to MessageLog.count.toDouble())))) }
        main.post { MessageLog.listen(send); send() }
        emitter.setCancellable { main.post { MessageLog.unlisten(send) } }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val density = context.resources.displayMetrics.density
        val heightDp = config.viewSize.second / density
        val tap = if (config.preview) null else PendingIntent.getBroadcast(context, 0,
            Intent(context, MessagesTapReceiver::class.java).setAction(MessagesTapReceiver.ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val draw = {
            val last = MessageLog.all().firstOrNull()
            val count = if (config.preview) 3 else MessageLog.count
            val sub = when {
                config.preview -> "Emma · 14:05"
                last == null -> "none yet"
                else -> "${last.title.ifEmpty { last.app }} · ${hhmm(last.at)}"
            }
            val valueSp = (heightDp * 0.40f).coerceIn(16f, 60f)
            val small = heightDp < 60f
            val views = RemoteViews(context.packageName, R.layout.view_messages)
            views.setTextViewText(R.id.tv_value, count.toString())
            views.setTextViewText(R.id.tv_sub, sub)
            views.setTextViewTextSize(R.id.tv_caption, TypedValue.COMPLEX_UNIT_SP, (valueSp * 0.32f).coerceIn(10f, 16f))
            views.setTextViewTextSize(R.id.tv_value, TypedValue.COMPLEX_UNIT_SP, valueSp)
            views.setTextViewTextSize(R.id.tv_sub, TypedValue.COMPLEX_UNIT_SP, (valueSp * 0.40f).coerceIn(10f, 20f))
            views.setViewVisibility(R.id.tv_caption, if (small) View.GONE else View.VISIBLE)
            tap?.let { views.setOnClickPendingIntent(R.id.field_root, it) }
            emitter.updateView(views)
        }
        main.post { MessageLog.listen(draw); draw() }
        emitter.setCancellable { main.post { MessageLog.unlisten(draw) } }
    }
}

/** Target of the tap on the Messages field: a broadcast into our own process, as in boschRange. */
class MessagesTapReceiver : BroadcastReceiver() {
    companion object { const val ACTION = "com.anpaiss.notifybridge.karoo.MESSAGES_TAP" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        BridgeExtension.instance?.showMessages()
    }
}
