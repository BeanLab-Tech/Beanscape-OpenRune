package org.rsmod.content.beanlab.admin.api

import java.security.MessageDigest
import java.security.SecureRandom

/** Token helpers. Only the SHA-256 of a token is ever stored; the raw value is shown once. */
public object AdminTokens {
    private const val PREFIX = "bsa_"
    private const val BYTES = 32
    private val random = SecureRandom()

    public fun generate(): String {
        val bytes = ByteArray(BYTES).also(random::nextBytes)
        return PREFIX + bytes.joinToString("") { "%02x".format(it) }
    }

    public fun hash(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) {
            "%02x".format(it)
        }
}
