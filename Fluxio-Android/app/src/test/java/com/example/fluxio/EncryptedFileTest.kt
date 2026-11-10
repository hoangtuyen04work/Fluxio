package com.example.fluxio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class EncryptedFileTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val id = UUID.randomUUID()

    @Test
    fun restoresEmptyAndMultiFrameFiles() {
        for (source in listOf(ByteArray(0), ByteArray(2 * 1024 * 1024 + 13) { it.toByte() })) {
            val restored = ByteArrayOutputStream()
            EncryptedFile.decrypt(id, encrypt(source).inputStream(), restored, key)
            assertArrayEquals(source, restored.toByteArray())
        }
    }

    @Test
    fun rejectsModifiedTruncatedAndSubstitutedFiles() {
        val encoded = encrypt(byteArrayOf(1, 2, 3))
        val modified = encoded.clone().apply { this[68] = (this[68].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            EncryptedFile.decrypt(id, modified.inputStream(), null, key)
        }
        assertThrows(EOFException::class.java) {
            EncryptedFile.decrypt(id, encoded.copyOf(encoded.size - 1).inputStream(), null, key)
        }
        assertThrows(AEADBadTagException::class.java) {
            EncryptedFile.decrypt(UUID.randomUUID(), encoded.inputStream(), null, key)
        }
    }

    private fun encrypt(source: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        EncryptedFile.encrypt(id, source.inputStream(), this, key)
    }.toByteArray()
}
