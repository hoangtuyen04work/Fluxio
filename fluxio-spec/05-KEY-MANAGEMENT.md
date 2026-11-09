# Key Management & Envelope Encryption Specification

**Specification Version:** 1.0.0-draft  
**Platform Reference:** Android Keystore System (API Level 26+)  

---

## 1. Key Architecture

Fluxio implements a two-tier key hierarchy using **Envelope Encryption**:

1. **Key Encryption Key (KEK / Master Key):**
   - Resides permanently inside the hardware-backed security module (TEE - Trusted Execution Environment or StrongBox Keymaster) of the user's Android device.
   - Non-exportable (`PURPOSE_ENCRYPT | PURPOSE_DECRYPT`).
   - Alias: `fluxio_master_kek_alias`.

2. **Data Encryption Key (DEK):**
   - 256-bit symmetric key (`AES-256`) generated randomly per file.
   - Used directly by Galois/Counter Mode (GCM) to encrypt/decrypt individual chunks.
   - Stored in server metadata **only in wrapped form** ($E_{\text{KEK}}(\text{DEK})$).

---

## 2. Android Keystore Integration Details

### 2.1. KEK Provisioning
```kotlin
val keyGenerator = KeyGenerator.getInstance(
    KeyProperties.KEY_ALGORITHM_AES,
    "AndroidKeyStore"
)
val spec = KeyGenParameterSpec.Builder(
    "fluxio_master_kek_alias",
    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
    .setKeySize(256)
    .setUserAuthenticationRequired(false) // Or true if biometric auth required
    .build()

keyGenerator.init(spec)
keyGenerator.generateKey()
```

### 2.2. DEK Wrapping (Encryption of DEK)
```kotlin
fun wrapDek(dekBytes: ByteArray, kekAlias: String): WrappedKeyResult {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val kek = keyStore.getKey(kekAlias, null) as SecretKey

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, kek)
    val iv = cipher.iv
    val wrappedDek = cipher.doFinal(dekBytes)

    return WrappedKeyResult(
        iv = Base64.encodeToString(iv, Base64.NO_WRAP),
        wrappedKey = Base64.encodeToString(wrappedDek, Base64.NO_WRAP)
    )
}
```

### 2.3. DEK Unwrapping (Decryption of DEK)
```kotlin
fun unwrapDek(wrappedKeyBase64: String, ivBase64: String, kekAlias: String): ByteArray {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val kek = keyStore.getKey(kekAlias, null) as SecretKey

    val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
    val encryptedDek = Base64.decode(wrappedKeyBase64, Base64.NO_WRAP)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val spec = GCMParameterSpec(128, iv)
    cipher.init(Cipher.DECRYPT_MODE, kek, spec)

    return cipher.doFinal(encryptedDek)
}
```

---

## 3. Storage and Database Security Rules

1. **Plaintext DEKs:**
   - **MUST NEVER** be written to SQLite/Room database.
   - **MUST NEVER** be persisted in SharedPreferences or DataStore.
   - **MUST NEVER** be logged to Logcat, error traces, or crash reporting services.

2. **Server-Side Key Storage:**
   - The Control Plane database (PostgreSQL `files` table) stores only `wrapped_dek` and `kek_salt_or_iv`.
   - The Server **cannot decrypt file chunks** because it does not possess the user's private KEK held in the mobile device's TEE.

