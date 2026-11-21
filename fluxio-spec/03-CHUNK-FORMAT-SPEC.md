# FLUXIO OPEN SPECIFICATION: ĐẶC TẢ ĐỊNH DẠNG NHỊ PHÂN CỦA CHUNK (CHUNK WIRE FORMAT SPEC)

**Tên dự án:** Fluxio – Tối ưu hiệu năng truyền tải & Bảo mật tệp tin trong ứng dụng Android  
**Phiên bản đặc tả:** 2.0 (Open Specification Draft)  
**Tiêu chuẩn mã hóa:** Big-Endian Binary Streams, RFC 5116 (AEAD)  
**Tương thích hạ tầng:** MinIO / AWS S3 Multipart Upload API  

---

## 1. Sơ đồ Cấu trúc Khung Nhị phân (Binary Wire Frame)

Mỗi chunk khi được đọc, mã hóa bằng `AES-256-GCM` và gửi lên Object Storage (MinIO) sẽ được đóng gói thành một khối nhị phân độc lập tự chứa (Self-contained Binary Frame) theo cấu trúc chuẩn:

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Nonce / IV (12 bytes)                    |
|                                                               |
|                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                               |          Chunk Index          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|          (4 bytes, Big-Endian Network Byte Order)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                     Ciphertext (N bytes)                      |
|           (Độ dài bằng đúng kích thước Plaintext gốc)         |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                 GCM Authentication Tag (16 bytes)             |
|                                                               |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

---

## 2. Bảng Định nghĩa Các Trường Dữ liệu (Field Specifications)

| Offset (Byte) | Tên trường | Kiểu dữ liệu | Độ dài | Mô tả chi tiết |
| :--- | :--- | :--- | :--- | :--- |
| `0x00 - 0x0B` | `nonce` | `byte[12]` | $12\text{ bytes}$ | Vector khởi tạo (IV) ngẫu nhiên an toàn dùng cho AES-GCM của chunk này. |
| `0x0C - 0x0F` | `chunkIndex` | `uint32` | $4\text{ bytes}$ | Chỉ số thứ tự của chunk trong tệp (bắt đầu từ 0), lưu theo chuẩn Big-Endian. |
| `0x10 - (L - 17)` | `ciphertext` | `byte[N]` | $N\text{ bytes}$ | Dữ liệu nhị phân sau khi mã hóa bằng AES-256-GCM. Không sử dụng padding. |
| `(L - 16) - (L - 1)`| `authTag` | `byte[16]` | $16\text{ bytes}$ | Nhãn xác thực 128-bit sinh ra từ phép mã hóa GCM (tính toán qua cả Ciphertext và AAD). |

> **Lưu ý triển khai trên Android / Java Security Provider:**  
> Khi gọi `cipher.doFinal(plaintext)` với `GCMParameterSpec(128, nonce)`, JVM tự động ghép $16\text{ bytes}$ Authentication Tag vào ngay phía sau `ciphertext`. Do đó, mảng byte đầu ra của phép mã hóa tự nhiên chính là `ciphertext + authTag`. Client chỉ cần ghi lần lượt `nonce (12B) + chunkIndex (4B) + cipherOutput` vào stream đẩy lên MinIO.

---

## 3. Quy tắc Phân đoạn & Kích thước Part (Chunk Sizing Rules)

Để bảo đảm tương thích tuyệt đối với đặc tả S3 / MinIO Multipart Upload:

### 3.1. Kích thước Chunk cấu hình ($S_{\text{chunk}}$)
- **Mặc định khuyến nghị:** $5\text{ MiB} \ (5,242,880\text{ bytes})$ hoặc $8\text{ MiB} \ (8,388,608\text{ bytes})$.
- **Ràng buộc cứng S3:** Ngoại trừ chunk cuối cùng (terminal chunk), mọi chunk tải lên MinIO Multipart **phải có kích thước $\ge 5\text{ MiB}$**.

### 3.2. Tính toán Kích thước Nhị phân Lưu trữ
Do mỗi chunk gắn thêm Header ($16\text{ bytes}$) và Tag ($16\text{ bytes}$):

$$\text{WirePartSize} = \text{PlaintextSize} + 12 \ (\text{Nonce}) + 4 \ (\text{Index}) + 16 \ (\text{Tag}) = \text{PlaintextSize} + 32\text{ bytes}$$

- **Đối với các chunk tiêu chuẩn ($i < N - 1$):**
  $$\text{WirePartSize} = S_{\text{chunk}} + 32\text{ bytes}$$
- **Đối với chunk cuối cùng ($i = N - 1$):**
  $$\text{WirePartSize}_{\text{final}} = (FileSize \pmod{S_{\text{chunk}}}) + 32\text{ bytes}$$
  *(Nếu file chia hết cho $S_{\text{chunk}}$ thì chunk cuối cùng có kích thước bằng $S_{\text{chunk}} + 32\text{ bytes}$)*.

---

## 4. Quy trình Đóng gói (Serialization) & Giải nén (Deserialization)

### 4.1. Mã giả Đóng gói tại Client (Android / Kotlin)
```kotlin
fun packEncryptedChunk(
    fileId: UUID,
    chunkIndex: Int,
    plaintextBytes: ByteArray,
    dek: ByteArray
): ByteArray {
    // 1. Sinh 12 bytes Nonce ngẫu nhiên
    val nonce = ByteArray(12).apply { SecureRandom().nextBytes(this) }
    
    // 2. Xây dựng AAD = fileId (16B) + chunkIndex (4B Big-Endian)
    val aad = ByteBuffer.allocate(20).apply {
        putLong(fileId.mostSignificantBits)
        putLong(fileId.leastSignificantBits)
        putInt(chunkIndex)
    }.array()

    // 3. Thực hiện AES-GCM Encryption
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(aad)
    val encryptedPayload = cipher.doFinal(plaintextBytes) // Đã bao gồm Ciphertext + 16B Tag

    // 4. Đóng gói Header: Nonce (12B) + chunkIndex (4B) + encryptedPayload
    return ByteBuffer.allocate(16 + encryptedPayload.size).apply {
        put(nonce)
        putInt(chunkIndex)
        put(encryptedPayload)
    }.array()
}
```

### 4.2. Mã giả Giải nén & Xác thực khi Tải về (Download)
```kotlin
fun unpackAndDecryptChunk(
    fileId: UUID,
    expectedIndex: Int,
    wireBytes: ByteArray,
    dek: ByteArray
): ByteArray {
    require(wireBytes.size >= 32) { "Chunk wire size quá nhỏ, không hợp lệ!" }

    val buffer = ByteBuffer.wrap(wireBytes)
    
    // 1. Đọc Nonce (12B) và chunkIndex (4B)
    val nonce = ByteArray(12).also { buffer.get(it) }
    val wireIndex = buffer.int
    require(wireIndex == expectedIndex) { 
        "Sai lệch thứ tự chunk! Kỳ vọng: $expectedIndex, Nhận được: $wireIndex" 
    }

    // 2. Tái tạo AAD để đối chiếu
    val aad = ByteBuffer.allocate(20).apply {
        putLong(fileId.mostSignificantBits)
        putLong(fileId.leastSignificantBits)
        putInt(expectedIndex)
    }.array()

    // 3. Tách Ciphertext + Tag
    val encryptedLength = wireBytes.size - 16
    val encryptedBytes = ByteArray(encryptedLength).also { buffer.get(it) }

    // 4. Giải mã và kiểm tra GCM Tag
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(aad)
    
    // Ném ngoại lệ AEADBadTagException nếu dữ liệu bị sửa đổi hoặc tráo AAD
    return cipher.doFinal(encryptedBytes)
}
```
