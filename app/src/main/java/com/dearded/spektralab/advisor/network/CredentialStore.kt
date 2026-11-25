package com.dearded.spektralab.advisor.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CredentialRef(val id: String, val endpointId: String, val origin: String, val revision: Long) {
    init { require(UUID.fromString(id).toString() == id && endpointId.isNotBlank() && revision >= 1) }
}

class CredentialUnavailable : IllegalStateException("Credential unavailable; enter it again")

internal fun interface CredentialKeySource {
    fun key(): SecretKey
    fun replacePermanentlyInvalidKey(): SecretKey = throw CredentialUnavailable()
}

internal class AndroidCredentialKey : CredentialKeySource {
    override fun replacePermanentlyInvalidKey(): SecretKey = synchronized(lock) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(ALIAS) }
        key()
    }
    override fun key(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    companion object { private const val ALIAS = "spektralab.advisor.credentials.v1"; private val lock = Any() }
}

/** Private no-backup ciphertext only. Authenticated metadata binds it to its endpoint and origin. */
class CredentialStore internal constructor(private val dir: File, private val keys: CredentialKeySource) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "advisor-credentials"), AndroidCredentialKey())

    @Synchronized fun store(endpoint: EndpointConfig, secret: CharArray): CredentialRef {
        require(secret.isNotEmpty() && secret.size <= 8192 && secret.none { it.isISOControl() })
        val ref = CredentialRef(UUID.randomUUID().toString(), endpoint.id, endpoint.origin,
            (endpoint.credential?.revision ?: 0) + 1)
        val bytes = String(secret).toByteArray(Charsets.UTF_8)
        val temporary = File(dir, "${ref.id}.tmp")
        try {
            check(dir.isDirectory || dir.mkdirs())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            try { cipher.init(Cipher.ENCRYPT_MODE, keys.key()) }
            catch (_: KeyPermanentlyInvalidatedException) {
                // Only an explicit new-key save can replace an unusable encryption key.
                cipher.init(Cipher.ENCRYPT_MODE, keys.replacePermanentlyInvalidKey())
            }
            cipher.updateAAD(metadata(ref))
            val encrypted = cipher.doFinal(bytes)
            val data = ByteBuffer.allocate(8 + cipher.iv.size + encrypted.size)
                .putInt(1).putInt(cipher.iv.size).put(cipher.iv).put(encrypted).array()
            java.io.FileOutputStream(temporary).use { it.write(data); it.fd.sync() }
            Files.move(temporary.toPath(), file(ref).toPath(), StandardCopyOption.ATOMIC_MOVE)
            return ref
        } catch (_: Exception) { throw CredentialUnavailable()
        } finally { bytes.fill(0); secret.fill('\u0000'); temporary.delete() }
    }

    @Synchronized fun <T> use(endpoint: EndpointConfig, block: (CharArray) -> T): T {
        val ref = endpoint.credential ?: throw CredentialUnavailable()
        if (ref.endpointId != endpoint.id || ref.origin != endpoint.origin) throw CredentialUnavailable()
        val plain = try {
            val source = file(ref)
            if (source.length() !in 36..33000) throw CredentialUnavailable()
            val data = ByteBuffer.wrap(source.readBytes())
            if (data.int != 1 || data.int != 12) throw CredentialUnavailable()
            val nonce = ByteArray(12).also(data::get)
            val payload = ByteArray(data.remaining()).also(data::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(128, nonce))
            cipher.updateAAD(metadata(ref))
            cipher.doFinal(payload)
        } catch (_: Exception) { throw CredentialUnavailable() }
        val chars = String(plain, Charsets.UTF_8).toCharArray()
        plain.fill(0)
        try { return block(chars) } finally { chars.fill('\u0000') }
    }

    @Synchronized fun remove(ref: CredentialRef): Boolean = !file(ref).exists() || file(ref).delete()
    private fun file(ref: CredentialRef) = File(dir, "${ref.id}.bin")
    private fun metadata(ref: CredentialRef) = "v1\n${ref.id}\n${ref.endpointId}\n${ref.origin}\n${ref.revision}".toByteArray(Charsets.UTF_8)
}
