package com.anpaiss.notifybridge.phone

import android.content.Context
import android.util.Base64

/** The keys of the Karoos paired with this phone, by their 8-byte id, in the app's private preferences. */
class PairStore(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("pair", Context.MODE_PRIVATE)

    val count: Int get() = prefs.all.size

    fun add(id: ByteArray, key: ByteArray) =
        prefs.edit().putString(PairCrypto.hex(id), Base64.encodeToString(key, Base64.NO_WRAP)).apply()

    fun find(id: ByteArray): ByteArray? =
        prefs.getString(PairCrypto.hex(id), null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun clear() = prefs.edit().clear().apply()
}
