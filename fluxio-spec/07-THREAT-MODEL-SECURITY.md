# Threat Model & Security Assurance Specification

**Specification Version:** 1.0.0-draft  
**Methodology:** STRIDE (Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, Elevation of Privilege)  

---

## 1. STRIDE Threat Matrix & Defenses

| STRIDE Category | Threat Scenario | Impact | Fluxio Specification Defense |
| :--- | :--- | :--- | :--- |
| **Spoofing** | Attacker impersonates legitimate user or injects fake upload sessions. | Unauthorized uploads, quota exhaustion. | Strict JWT bearer token authentication on all Control Plane endpoints; Presigned URLs signed with MinIO access secrets. |
| **Tampering** | Man-in-the-middle or malicious storage admin modifies chunk ciphertext bytes. | Data corruption or malware injection. | **AES-256-GCM 16-byte Authentication Tag**. Decryption immediately aborts and discards corrupt data. |
| **Tampering (Reordering)** | Attacker swaps Chunk #2 with Chunk #4, or swaps chunks between different files. | Corrupted file assembly without tag failure. | **AAD Cryptographic Binding**: `fileId + chunkIndex` is passed into GCM calculation; any swapped chunk fails tag check. |
| **Repudiation** | User claims they did not download, delete, or create share links for a document. | Loss of forensic accountability. | **Audit Log Engine**: Non-repudiable database logging of `userId`, `fileId`, `action`, `ipAddress`, and `userAgent`. |
| **Information Disclosure** | Data breach at Object Storage provider (MinIO / S3 buckets exposed public). | Plaintext exposure of confidential files. | **End-to-End Client Encryption (Option B)**: Buckets store only ciphertext. DEK is never stored in plaintext on storage or server. |
| **Information Disclosure (Mobile)** | Reverse engineer extracts encryption keys via rooted Android phone memory dump. | Decryption of local file cache. | **Android Keystore (TEE / StrongBox)**: KEK never leaves hardware module. Plaintext DEK resides in memory only during active transfer and is zeroized immediately. |
| **Denial of Service** | Rogue client initiates millions of uncommitted multipart uploads. | Storage and DB table exhaustion. | **Transfer TTL**: Control plane marks expired sessions (`ABORTED`) and invokes S3 `AbortMultipartUpload` automatically after 24 hours. |
| **Elevation of Privilege** | User changes file ID in request URL to access another user's file (IDOR). | Unauthorized data access. | **Document-Level Authorization**: Server checks ownership / explicit permissions (`OWNER`, `EDITOR`, `VIEWER`) per `fileId`. |

---

## 2. Cryptographic Security Invariants

All implementations conforming to this specification **MUST** maintain the following invariants:

1. **Invariant 1 (Nonce Uniqueness):** The tuple `(DEK, Nonce)` MUST NOT be reused. Reusing an IV in AES-GCM destroys confidentiality and authenticity.
2. **Invariant 2 (Zero Plaintext on Wire):** Plaintext file bytes MUST NOT be transmitted to any domain other than local client storage.
3. **Invariant 3 (Tag Verification Precedence):** A chunk's plaintext MUST NOT be written to persistent storage or exposed to the user application until the GCM authentication tag has been fully verified.
4. **Invariant 4 (Strict Scope Isolation):** A Presigned URL granted for Part $N$ MUST NOT be valid for any part other than $N$, and MUST expire within 15 minutes.

