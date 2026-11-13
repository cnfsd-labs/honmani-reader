package app.honmani.reader.data.opds

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The Keystore could not encrypt a secret, so it was not stored. */
class CredentialException(cause: Throwable) : IOException("This phone could not store the password securely", cause)

/** Encrypts secrets (catalogue passwords) for storage. */
interface CredentialCipher {
    /** Throws [CredentialException] if the Keystore fails. */
    fun encrypt(plain: String): String

    /** Null if the secret cannot be decrypted, for example after the key was lost. */
    fun decrypt(encrypted: String): String?
}

/**
 * AES-GCM with a key that lives in the Android Keystore and never leaves it. The stored text is
 * the IV followed by the ciphertext, in Base64.
 */
class KeystoreCredentialCipher(private val alias: String = "honmani-credentials") : CredentialCipher {
    override fun encrypt(plain: String): String {
        val sealed = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            throw CredentialException(e)
        } catch (e: ProviderException) {
            // Some Keystore failures (a key the device can no longer use) come as this.
            throw CredentialException(e)
        } catch (e: IOException) {
            throw CredentialException(e)
        }
        return Base64.getEncoder().encodeToString(sealed)
    }

    override fun decrypt(encrypted: String): String? = try {
        val sealed = Base64.getDecoder().decode(encrypted)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ProviderException) {
        // Some Keystore failures (a key the device can no longer use) come as this.
        null
    } catch (_: IOException) {
        null
    }

    /** Synchronized: two first uses at once would each make a key, and the second replace the first. */
    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
