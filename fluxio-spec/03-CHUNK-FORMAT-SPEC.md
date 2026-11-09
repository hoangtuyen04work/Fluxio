# Chunk Wire Format Specification

**Specification Version:** 1.0.0-draft  
**Encoding:** Big-Endian Binary Streams  

---

## 1. Chunk Wire Representation

Each independent chunk transmitted to or from Object Storage (MinIO / S3) is serialized into a standalone self-contained binary frame:

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
|                 (Variable length: 1 to ChunkSize)             |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                 GCM Authentication Tag (16 bytes)             |
|                                                               |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

---

## 2. Field Definitions

| Offset (Bytes) | Field Name | Data Type | Length (Bytes) | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0x00 - 0x0B` | `nonce` | `byte[12]` | 12 | The initialization vector (IV) used in AES-GCM. |
| `0x0C - 0x0F` | `chunkIndex`| `uint32` | 4 | Zero-based index of this chunk within the file. Big-Endian. |
| `0x10 - (L - 17)`| `ciphertext` | `byte[N]` | $N$ | Raw encrypted payload. Length $N = \text{PlaintextSize}$. |
| `(L - 16) - (L - 1)` | `authTag` | `byte[16]` | 16 | Galois Authentication Tag (128 bits) computed over AAD and Ciphertext. |

> **Note on Standard Java/Android `Cipher` output:**  
> When running `Cipher.doFinal()` with `GCMParameterSpec(128, nonce)`, Java appends the 16-byte authentication tag directly to the end of the ciphertext stream. Thus, the payload written is naturally `Ciphertext + Tag`.

---

## 3. Chunk Sizing Rules

1. **Configured Chunk Size ($S_{chunk}$):**
   - Recommended: $5\text{ MiB} \ (5,242,880 \text{ bytes})$ or $8\text{ MiB} \ (8,388,608 \text{ bytes})$.
   - Compliance Requirement: Must conform to S3/MinIO multipart minimum part size ($\ge 5\text{ MiB}$) for all parts except the terminal part.

2. **Total Stored Size per Part:**
   $$\text{WirePartSize} = \text{NonceSize (12)} + \text{IndexSize (4)} + \text{PlaintextSize} + \text{TagSize (16)} = \text{PlaintextSize} + 32 \text{ bytes}$$

3. **Terminal Part:**
   - The final part ($i = N-1$) has a size of:
     $$\text{FinalWirePartSize} = (FileSize \pmod{S_{chunk}}) + 32 \text{ bytes}$$

