# Cryptographic Specification

**Specification Version:** 1.0.0-draft  
**Standard Compliance:** NIST SP 800-38D (GCM Mode), RFC 5116 (AEAD)  

---

## 1. Cryptographic Primitives

| Component | Standard / Primitive | Parameters | Notes |
| :--- | :--- | :--- | :--- |
| **Cipher Algorithm** | AES-GCM (Authenticated Encryption with Associated Data) | 256-bit Key | Galois/Counter Mode |
| **Nonce / IV Length** | Deterministic or CSPRNG 96-bit | 12 bytes | Strictly unique per key/chunk invocation |
| **Auth Tag Length** | GCM Authentication Tag | 128-bit (16 bytes) | Integrity & authenticity validation |
| **Master Key (KEK)** | Android Keystore Key | AES-256-GCM or RSA-OAEP-256 | Hardware-backed (TEE / StrongBox) |
| **File Key (DEK)** | Symmetric Key | 256-bit CSPRNG (`SecureRandom`) | Ephemeral, generated per file |
| **Key Derivation (Opt)**| HKDF-SHA256 (RFC 5869) | Info: `Fluxio-Chunk-Key-v1` | For subkey isolation if needed |

---

## 2. Key Hierarchy & Envelope Encryption

```text
+-------------------------------------------------------+
|                Android Keystore (TEE)                 |
|             Key Encryption Key (KEK - 256 bit)        |
+-------------------------------------------------------+
                           |
                     (Wraps / Encrypts)
                           v
+-------------------------------------------------------+
|          Data Encryption Key (DEK - 256 bit)          |
|    (Generated randomly per file, held in RAM only)    |
+-------------------------------------------------------+
                           |
                     (Encrypts)
                           v
+-------------------------------------------------------+
|  Chunk 0 (AES-GCM) | Chunk 1 (AES-GCM) | Chunk N ...   |
+-------------------------------------------------------+
```

### 2.1. DEK Generation & Memory Zeroization
* The Client generates a 32-byte DEK using a cryptographically secure random number generator:
  ```kotlin
  val dek = ByteArray(32)
  SecureRandom().nextBytes(dek)
  ```
* **Memory Sanitation Rule:** When transfer processing ends or fails, in-memory buffers holding plaintext DEK bytes **MUST** be explicitly scrubbed using `java.util.Arrays.fill(dek, 0.toByte())` to prevent heap dump extraction.

---

## 3. AEAD Construction & Additional Authenticated Data (AAD)

For each chunk with index $i \in [0, N-1]$ belonging to file with UUID `fileId`:

### 3.1. Nonce Generation
The 12-byte Nonce **MUST NEVER** be repeated for the same DEK.
* **Deterministic Strategy:** A 12-byte nonce composed of:
  - 8-byte random transfer session salt
  - 4-byte Big-Endian chunk index $i$
* **Or Random Strategy:** 12 bytes sourced directly from `SecureRandom`.

### 3.2. AAD Construction
The Additional Authenticated Data ensures cryptographic binding between the encrypted content and its spatial location in the file:

$$\text{AAD} = \text{Binary}(\text{fileId}) \ || \ \text{BigEndian32}(\text{chunkIndex})$$

- `fileId`: 16-byte raw UUID representation (or UTF-8 bytes of canonical UUID string).
- `chunkIndex`: 4-byte signed/unsigned integer in Big-Endian network order.

### 3.3. AEAD Execution
$$\text{Ciphertext}, \text{Tag} = \text{AES-GCM-Encrypt}(\text{Key}=\text{DEK}, \text{IV}=\text{Nonce}, \text{Plaintext}=\text{ChunkData}, \text{AAD}=\text{AAD})$$

On decryption:
$$\text{Plaintext} = \text{AES-GCM-Decrypt}(\text{Key}=\text{DEK}, \text{IV}=\text{Nonce}, \text{Ciphertext}=\text{Ciphertext}, \text{Tag}=\text{Tag}, \text{AAD}=\text{AAD})$$

If the tag verification fails, the entire chunk is discarded immediately, and an integrity violation is logged.

