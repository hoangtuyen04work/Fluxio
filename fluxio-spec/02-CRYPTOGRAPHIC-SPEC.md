# FLUXIO OPEN SPECIFICATION: ĐẶC TẢ MÃ HÓA & MÔ HÌNH KHÓA (CRYPTOGRAPHIC SPECIFICATION)

**Tên dự án:** Fluxio – Tối ưu hiệu năng truyền tải & Bảo mật tệp tin trong ứng dụng Android  
**Phiên bản đặc tả:** 2.0 (Open Specification Draft)  
**Tiêu chuẩn tuân thủ:** NIST SP 800-38D (Galois/Counter Mode - GCM), RFC 5116 (AEAD Algorithms)  

---

## 1. Các Nguyên thủy Mật mã (Cryptographic Primitives)

Hệ thống Fluxio sử dụng mô hình mã hóa xác thực **AEAD (Authenticated Encryption with Associated Data)** với thuật toán đối xứng chuẩn quân sự và phần cứng bảo vệ cấp thiết bị:

| Thành phần | Thuật toán / Tiêu chuẩn | Độ dài / Tham số | Mô tả & Vai trò |
| :--- | :--- | :--- | :--- |
| **Cipher Algorithm** | `AES-256-GCM` | Khóa 256-bit ($32\text{ bytes}$) | Mã hóa đối xứng luồng và xác thực toàn vẹn độc lập từng chunk dữ liệu. |
| **Nonce / IV** | CSPRNG / Deterministic IV | 96-bit ($12\text{ bytes}$) | Giá trị khởi tạo vector duy nhất cho từng chunk. Tuyệt đối không lặp lại với cùng một DEK. |
| **Authentication Tag** | Galois Authentication Tag | 128-bit ($16\text{ bytes}$) | Nhãn chứng minh tính toàn vẹn và xác thực nguồn gốc dữ liệu của chunk. |
| **Additional Authenticated Data (AAD)** | Binary Context Binding | Biến thiên ($20\text{ bytes}$) | Ràng buộc ngữ cảnh mật mã: $\text{fileId} \parallel \text{chunkIndex}$. |
| **Master Key (KEK)** | Hardware-backed Key | 256-bit AES hoặc RSA-2048 | Khóa chủ sinh và quản lý trong Android Keystore (vùng TEE / StrongBox). |
| **Data Encryption Key (DEK)** | CSPRNG Symmetric Key | 256-bit ($32\text{ bytes}$) | Khóa mã hóa dữ liệu ngẫu nhiên sinh cho từng file, chỉ tồn tại dạng bọc (wrapped) khi lưu trữ. |

---

## 2. Mô hình phân tầng khóa & Mã hóa phong bì (Envelope Encryption)

Để bảo đảm tính bảo mật đầu cuối mà không phải lưu khóa gốc (plaintext key) trên máy chủ hoặc cơ sở dữ liệu, Fluxio áp dụng mô hình **Mã hóa phong bì (Envelope Encryption)** hai tầng:

```
+-----------------------------------------------------------------+
|                    Android Keystore (TEE / StrongBox)           |
|                Key Encryption Key (KEK - 256 bit AES-GCM)       |
+-----------------------------------------------------------------+
                                 |
                   (Bọc khóa / Wrap DEK bằng KEK)
                                 v
+-----------------------------------------------------------------+
|               Data Encryption Key (DEK - 256 bit CSPRNG)        |
|        (Chỉ giải mã tạm thời trong RAM khi truyền tải dữ liệu)   |
+-----------------------------------------------------------------+
                                 |
                  (Mã hóa độc lập từng Chunk)
                                 v
+-----------------------------------------------------------------+
| Chunk 0 (AES-GCM)  |  Chunk 1 (AES-GCM)  |  Chunk N (AES-GCM)... |
+-----------------------------------------------------------------+
```

### 2.1. Vòng đời của Data Encryption Key (DEK)
1. **Khởi tạo:** Khi người dùng chọn upload một file, Client sinh một mảng $32\text{ bytes}$ ngẫu nhiên thông qua `SecureRandom`:
   ```kotlin
   val dek = ByteArray(32).apply {
       SecureRandom().nextBytes(this)
   }
   ```
2. **Bọc khóa (Key Wrapping):** Client sử dụng KEK trong Android Keystore để mã hóa DEK:
   $$\text{WrappedDEK} = \text{AES-GCM-Encrypt}_{\text{KEK}}(\text{DEK})$$
   Chuỗi `WrappedDEK` kèm theo IV của KEK được gửi lên Control Plane API trong payload khởi tạo upload session (`POST /api/v1/transfers/uploads`).
3. **Mở khóa (Key Unwrapping):** Khi download file, client nhận `WrappedDEK` từ Server, sử dụng KEK phần cứng trên máy để giải mã thành DEK trong RAM:
   $$\text{DEK} = \text{AES-GCM-Decrypt}_{\text{KEK}}(\text{WrappedDEK})$$
4. **Quy tắc Zeroize bộ nhớ RAM:** Ngay khi phiên mã hóa/giải mã toàn bộ các chunk kết thúc hoặc bị hủy, mảng byte chứa DEK thô trong bộ nhớ phải được xóa sạch bằng giá trị 0:
   ```kotlin
   java.util.Arrays.fill(dek, 0.toByte())
   ```
   **Quy định cấm:** Tuyệt đối không lưu plaintext DEK vào `SharedPreferences`, `DataStore`, `Room Database` hay truyền trực tiếp không bọc qua API.

---

## 3. Đặc tả Mã hóa Chunk & Gắn kết ngữ cảnh (AAD Enforcement)

Mỗi chunk được coi là một đơn vị dữ liệu AEAD độc lập. Để ngăn chặn các cuộc tấn công tráo đổi thứ tự hoặc tráo chunk giữa các tệp tin (Chunk Reordering & Swapping Attacks), quy trình tính toán được quy định như sau:

### 3.1. Cấu trúc Nonce (12 bytes)
Mỗi chunk bắt buộc phải có một Nonce riêng biệt. Sử dụng một trong hai chiến lược:
- **Chiến lược ngẫu nhiên an toàn (Khuyến nghị):** Sinh $12\text{ bytes}$ độc lập bằng `SecureRandom.nextBytes()` cho từng chunk.
- **Chiến lược dẫn xuất tuần tự:** $8\text{ bytes}$ Session Salt ngẫu nhiên $\parallel$ $4\text{ bytes}$ Big-Endian `chunkIndex`.

### 3.2. Cấu trúc Additional Authenticated Data (AAD)
AAD là dữ liệu không bị mã hóa nhưng được đưa vào thuật toán để tính toán giá trị băm xác thực của Authentication Tag:

$$\text{AAD} = \text{Binary16}(\text{fileId}) \ \parallel \ \text{BigEndian32}(\text{chunkIndex})$$

- `fileId` ($16\text{ bytes}$): Mã định danh UUID v4 của tệp tin biểu diễn dưới dạng nhị phân 128-bit (hoặc chuỗi bytes UTF-8 chuẩn tắc).
- `chunkIndex` ($4\text{ bytes}$): Số thứ tự index của chunk bắt đầu từ $0$, biểu diễn theo thứ tự byte chuẩn mạng (Big-Endian).

> **Ý nghĩa an ninh:** Nếu kẻ tấn công trên đường truyền cố tình tráo Chunk số 5 vào vị trí Chunk số 2, hoặc lấy Chunk số 2 của file A thay cho file B, khi giải mã với AAD của vị trí hiện tại, thuật toán AES-GCM sẽ phát hiện sai lệch và lập tức kích hoạt ngoại lệ `AEADBadTagException`, từ chối nhận dữ liệu hỏng.

### 3.3. Quy trình Mã hóa (Encryption Formula)
$$\text{Ciphertext}, \text{Tag}_{16} = \text{AES-GCM-Encrypt}(\text{Key}=\text{DEK}, \text{IV}=\text{Nonce}_{12}, \text{Plaintext}=\text{ChunkData}, \text{AAD}=\text{AAD})$$

### 3.4. Quy trình Giải mã & Kiểm tra Toàn vẹn (Decryption & Authentication)
$$\text{Plaintext} = \text{AES-GCM-Decrypt}(\text{Key}=\text{DEK}, \text{IV}=\text{Nonce}_{12}, \text{Ciphertext}=\text{Ciphertext}, \text{Tag}=\text{Tag}_{16}, \text{AAD}=\text{AAD})$$

Nếu Authentication Tag không khớp, hàm giải mã sẽ ném lỗi:
- Bản rõ không được phép ghi ra đĩa bộ nhớ trong hoặc thư mục download.
- Tiến trình tải dừng lại, đánh dấu chunk đó là `FAILED` và yêu cầu tải lại chunk từ Object Storage.
