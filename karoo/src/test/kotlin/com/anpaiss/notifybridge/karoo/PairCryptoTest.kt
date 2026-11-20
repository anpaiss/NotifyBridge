package com.anpaiss.notifybridge.karoo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairCryptoTest {

    @Test
    fun bothSidesGetTheSameKeyIdAndCode() {
        val karoo = PairCrypto.newKeyPair(); val phone = PairCrypto.newKeyPair()
        val k = PairCrypto.derive(karoo, karoo.public.encoded, phone.public.encoded)
        val p = PairCrypto.derive(phone, karoo.public.encoded, phone.public.encoded)
        assertArrayEquals(k.key, p.key)
        assertArrayEquals(k.id, p.id)
        assertEquals(k.code, p.code)
        assertTrue(k.code.matches(Regex("\\d{3} \\d{3}")))
    }

    @Test
    fun someoneInBetweenGetsOtherCodes() {
        val karoo = PairCrypto.newKeyPair(); val phone = PairCrypto.newKeyPair(); val mitm = PairCrypto.newKeyPair()
        // He talks to the Karoo as a phone, and to the phone as a Karoo
        val onKaroo = PairCrypto.derive(karoo, karoo.public.encoded, mitm.public.encoded)
        val onPhone = PairCrypto.derive(phone, mitm.public.encoded, phone.public.encoded)
        assertNotEquals(onKaroo.code, onPhone.code)
    }

    @Test
    fun sealedFrameOpensOnlyWithItsKeyAndUnaltered() {
        val a = PairCrypto.newKeyPair(); val b = PairCrypto.newKeyPair()
        val key = PairCrypto.derive(a, a.public.encoded, b.public.encoded).key
        val other = PairCrypto.sha("x".toByteArray())
        val plain = """{"app":"WhatsApp","title":"Tom","text":"at the hut"}""".toByteArray()
        val frame = PairCrypto.seal(key, plain)
        assertEquals(plain.size + PairCrypto.OVERHEAD, frame.size)
        assertArrayEquals(plain, PairCrypto.open(key, frame))
        assertNull(PairCrypto.open(other, frame))
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()
        assertNull(PairCrypto.open(key, frame))
    }

    @Test
    fun commitmentBindsThePublicKey() {
        val a = PairCrypto.newKeyPair(); val b = PairCrypto.newKeyPair()
        assertArrayEquals(PairCrypto.commit(a.public.encoded), PairCrypto.commit(a.public.encoded))
        assertNotEquals(PairCrypto.hex(PairCrypto.commit(a.public.encoded)), PairCrypto.hex(PairCrypto.commit(b.public.encoded)))
    }
}
