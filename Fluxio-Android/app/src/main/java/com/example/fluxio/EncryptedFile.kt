package com.example.fluxio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object EncryptedFile {
    private const val magic = 0x46584D31 // FXM1
    private const val chunkSize = 1024 * 1024
    private const val maxChunks = 4096
    private const val nonceSize = 12
    private const val tagSize = 16
    private const val keySize = 32

    val maxEncodedBytes = 4L + nonceSize + keySize + tagSize +
        maxChunks.toLong() * chunkSize + (maxChunks + 1L) * (4 + tagSize)

    fun encrypt(fileId: UUID, source: InputStream, target: OutputStream, wrappingKey: SecretKey) {
        val output = DataOutputStream(target)
        val keyBytes = ByteArray(keySize).also(SecureRandom()::nextBytes)
        val buffer = ByteArray(chunkSize)
        try {
            val wrap = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, wrappingKey)
                updateAAD(aad(fileId, -1, keySize))
            }
            output.writeInt(magic)
            output.write(wrap.iv)
            output.write(wrap.doFinal(keyBytes))
            val key = SecretKeySpec(keyBytes, "AES")

            var index = 0
            while (true) {
                var size = 0
                while (size < buffer.size) {
                    val count = source.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                require(size == 0 || index < maxChunks) { "Maximum file size is 4 GiB." }
                output.writeInt(size)
                val cipher = frameCipher(Cipher.ENCRYPT_MODE, key, fileId, index, size)
                output.write(cipher.doFinal(buffer, 0, size))
                if (size == 0) break
                index++
            }
            output.flush()
        } finally {
            keyBytes.fill(0)
            buffer.fill(0)
        }
    }

    fun decrypt(fileId: UUID, source: InputStream, target: OutputStream?, wrappingKey: SecretKey) {
        val input = DataInputStream(source)
        require(input.readInt() == magic) { "Unsupported encrypted file format." }
        val nonce = ByteArray(nonceSize).also(input::readFully)
        val wrapped = ByteArray(keySize + tagSize).also(input::readFully)
        val keyBytes = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, nonce))
            updateAAD(aad(fileId, -1, keySize))
            doFinal(wrapped)
        }
        try {
            val key = SecretKeySpec(keyBytes, "AES")
            for (index in 0..maxChunks) {
                val size = input.readInt()
                require(size in 0..chunkSize && (size == 0 || index < maxChunks)) {
                    "Invalid encrypted frame size."
                }
                val ciphertext = ByteArray(size + tagSize).also(input::readFully)
                val plaintext = frameCipher(Cipher.DECRYPT_MODE, key, fileId, index, size)
                    .doFinal(ciphertext)
                try {
                    if (size == 0) {
                        require(input.read() == -1) { "Unexpected data after the final frame." }
                        return
                    }
                    target?.write(plaintext)
                } finally {
                    plaintext.fill(0)
                }
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun frameCipher(mode: Int, key: SecretKey, fileId: UUID, index: Int, size: Int): Cipher {
        // Each file has a fresh key, so its frame index is unique under that key.
        val nonce = ByteBuffer.allocate(nonceSize).putLong(index.toLong()).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(aad(fileId, index, size))
        }
    }

    private fun aad(fileId: UUID, index: Int, size: Int): ByteArray =
        ByteBuffer.allocate(28).putInt(magic)
            .putLong(fileId.mostSignificantBits).putLong(fileId.leastSignificantBits)
            .putInt(index).putInt(size).array()
}
