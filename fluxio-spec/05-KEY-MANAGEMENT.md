# FLUXIO OPEN SPECIFICATION: QUẢN LÝ KHÓA & MÃ HÓA PHONG BÌ (KEY MANAGEMENT & KEYSTORE SPEC)

**Tên dự án:** Fluxio – Tối ưu hiệu năng truyền tải & Bảo mật tệp tin trong ứng dụng Android  
**Phiên bản đặc tả:** 2.0 (Open Specification Draft)  
**Tiêu chuẩn phần cứng:** Android Keystore System (API Level 26+), TEE (Trusted Execution Environment) / StrongBox Keymaster  

---

## 1. Kiến trúc Khóa & Mô hình Envelope Encryption

Fluxio xây dựng cơ chế quản lý khóa phân cấp 2 tầng dựa trên nguyên tắc **Mã hóa phong bì (Envelope Encryption)**:

```text
[Thiết bị Android]
+-------------------------------------------------------------+
|               Android Keystore (Hardware TEE)               |
|            Master Key / KEK (Key Encryption Key)            |
|       (Thuật toán: AES-256-GCM, bí mật vĩnh viễn trên máy)   |
+-------------------------------------------------------------+
                              |
                (Mã hóa bọc khóa / Wrap DEK)
                              v
+-------------------------------------------------------------+
|             Data Encryption Key (DEK - 256 bit)             |
|     (Sinh ngẫu nhiên cho từng tệp tin qua SecureRandom)     |
+-------------------------------------------------------------+
         |                                           |
         v (Lưu trữ Metadata)                        v (Xử lý Data)
+-------------------------------+           +---------------------------+
| PostgreSQL / Server Metadata  |           | Mã hóa / Giải mã          |
| (Chỉ lưu Wrapped DEK dạng     |           | độc lập từng Chunk        |
| ciphertext, Server KHÔNG THỂ  |           | (DEK chỉ nằm trong RAM    |
| giải mã được tệp tin)         |           | tạm thời rồi zeroize)     |
+-------------------------------+           +---------------------------+
```

---

## 2. Đặc tả Triển khai trên Android Keystore

### 2.1. Khởi tạo Master Key (KEK) trong phần cứng TEE
```kotlin
fun getOrCreateMasterKek(alias: String = "fluxio_master_kek_alias"): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    
    if (keyStore.containsAlias(alias)) {
        return keyStore.getKey(alias, null) as SecretKey
    }

    val keyGenerator = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore"
    )
    val spec = KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUserAuthenticationRequired(false) // Bật true nếu yêu cầu xác thực vân tay/biometric
        .build()

    keyGenerator.init(spec)
    return keyGenerator.generateKey()
}
```

### 2.2. Bọc khóa DEK (Wrap DEK)
```kotlin
data class WrappedKeyResult(
    val wrappedDekBase64: String,
    val kekIvBase64: String
)

fun wrapDek(dekPlaintext: ByteArray, kekAlias: String = "fluxio_master_kek_alias"): WrappedKeyResult {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val kek = keyStore.getKey(kekAlias, null) as SecretKey

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, kek)
    val iv = cipher.iv
    val wrappedBytes = cipher.doFinal(dekPlaintext)

    return WrappedKeyResult(
        wrappedDekBase64 = Base64.encodeToString(wrappedBytes, Base64.NO_WRAP),
        kekIvBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
    )
}
```

### 2.3. Mở bọc khóa DEK (Unwrap DEK)
```kotlin
fun unwrapDek(
    wrappedDekBase64: String,
    kekIvBase64: String,
    kekAlias: String = "fluxio_master_kek_alias"
): ByteArray {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val kek = keyStore.getKey(kekAlias, null) as SecretKey

    val iv = Base64.decode(kekIvBase64, Base64.NO_WRAP)
    val wrappedBytes = Base64.decode(wrappedDekBase64, Base64.NO_WRAP)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val spec = GCMParameterSpec(128, iv)
    cipher.init(Cipher.DECRYPT_MODE, kek, spec)

    return cipher.doFinal(wrappedBytes)
}
```

---

## 3. Các Nguyên tắc Bất biến về Bảo vệ Khóa (Key Hygiene Invariants)

1. **Tuyệt đối không lưu Plaintext DEK vào bộ nhớ bền vững:**
   - Cấm ghi khóa chưa mã hóa vào SQLite, Room, SharedPreferences, DataStore hoặc bộ nhớ ngoài (SD Card).
2. **Zeroize bộ nhớ RAM:**
   - Các biến giữ byte của DEK sau khi thực hiện xong stream chunking bắt buộc phải được ghi đè bằng giá trị byte `0` qua `Arrays.fill(dek, 0.toByte())`.
3. **Zero-Knowledge Backend:**
   - Máy chủ lưu trữ và cơ sở dữ liệu PostgreSQL tuyệt đối không có khả năng giải mã tệp tin của người dùng vì máy chủ chỉ nắm giữ `wrapped_dek` và hoàn toàn không nắm giữ `KEK` của thiết bị di động.
