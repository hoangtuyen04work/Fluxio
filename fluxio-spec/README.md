# Fluxio Open Specification Index

This directory contains the normative technical specification documents for the **Fluxio** platform (Secure & High-Performance Mobile File Transmission System).

---

## Document Index

| Spec Document | Description | Format / Standard |
| :--- | :--- | :--- |
| [**`01-OVERVIEW.md`**](file:///d:/KMA/Fluxio/fluxio-spec/01-OVERVIEW.md) | Architectural principles, system scope, and core design paradigms. | Markdown |
| [**`02-CRYPTOGRAPHIC-SPEC.md`**](file:///d:/KMA/Fluxio/fluxio-spec/02-CRYPTOGRAPHIC-SPEC.md) | Cryptographic primitives, AES-256-GCM, Nonce rules, AAD binding, and memory zeroization. | NIST SP 800-38D, RFC 5116 |
| [**`03-CHUNK-FORMAT-SPEC.md`**](file:///d:/KMA/Fluxio/fluxio-spec/03-CHUNK-FORMAT-SPEC.md) | Chunk wire layout, binary frame structure, header offsets, and S3 multipart chunk sizing. | Big-Endian Binary Protocol |
| [**`04-PROTOCOL-LIFECYCLE.md`**](file:///d:/KMA/Fluxio/fluxio-spec/04-PROTOCOL-LIFECYCLE.md) | Upload/Download state machines, sequence diagrams, and chunk-level resume protocol. | Mermaid & Markdown |
| [**`05-KEY-MANAGEMENT.md`**](file:///d:/KMA/Fluxio/fluxio-spec/05-KEY-MANAGEMENT.md) | Envelope encryption, Android Keystore (TEE/StrongBox) KEK protection, DEK wrapping & unwrapping. | Kotlin / Android Security |
| [**`06-OPENAPI.yaml`**](file:///d:/KMA/Fluxio/fluxio-spec/06-OPENAPI.yaml) | Full Control Plane REST API specifications (Auth, Transfers, Parts, Files, Shares). | OpenAPI 3.1.0 |
| [**`07-THREAT-MODEL-SECURITY.md`**](file:///d:/KMA/Fluxio/fluxio-spec/07-THREAT-MODEL-SECURITY.md) | STRIDE Threat Model, attack vectors & mitigations, and 4 core security invariants. | STRIDE Matrix |
| [**`08-DATABASE-STORAGE-SPEC.md`**](file:///d:/KMA/Fluxio/fluxio-spec/08-DATABASE-STORAGE-SPEC.md) | Database schemas for PostgreSQL (Server) and Android Room (Client local transfers & chunks). | ER Diagram & SQL/Kotlin |

