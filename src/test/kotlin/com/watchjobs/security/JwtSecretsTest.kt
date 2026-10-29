package com.watchjobs.security

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JwtSecretsTest {
    @Test
    fun `aceita Base64 padrão e Base64URL do mesmo material`() {
        val raw = ByteArray(32) { it.toByte() }
        val standard = Base64.getEncoder().encodeToString(raw)
        val url = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

        assertEquals(raw.toList(), JwtSecrets.decode(standard).toList())
        assertEquals(raw.toList(), JwtSecrets.decode(url).toList())
        assertEquals(raw.toList(), JwtSecrets.decode("  $standard\n").toList())
    }

    @Test
    fun `aceita underscore do alfabeto Base64URL`() {
        val raw = ByteArray(32) { 0xFF.toByte() }
        val url = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

        assertTrue(url.contains('_'))
        assertEquals(raw.toList(), JwtSecrets.decode(url).toList())
    }

    @Test
    fun `rejeita valor que não é Base64 sem devolver o segredo`() {
        val failure = assertFailsWith<IllegalStateException> {
            JwtSecrets.decode("nao-e-segredo-jwt-com-ponto.e_.underline")
        }
        assertTrue(failure.message!!.contains("underscore=true"))
        assertTrue(failure.message!!.contains("dot=true"))
        assertTrue(!failure.message!!.contains("nao-e-segredo"))
    }
}
