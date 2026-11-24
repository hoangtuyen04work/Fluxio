# Proposal

> Theo dõi trạng thái thực hiện tại [`progress.md`](./progress.md).

## Why

Fluxio must first establish a fast, resumable, and resource-efficient transfer foundation for Android. Client-side encryption currently adds CPU, storage, key-management, and compatibility complexity before transfer performance has been measured and stabilized.

## What Changes

- **BREAKING:** remove client-side FXM1 encryption and installation-bound restore from the target transfer path.
- Upload and download file bytes directly between Android and private MinIO storage through short-lived presigned URLs.
- Replace whole-file temporary buffering with multipart streaming and bounded in-flight buffers.
- Add resumable upload state, retry, reconciliation, cancellation, and cleanup.
- Establish benchmark-based acceptance criteria before choosing final part size and concurrency defaults.
- Require comparative baseline modes that isolate the effects of streaming, chunking, bounded parallelism, and asynchronous resume.
- Keep segmented parallel download as an optional experiment; ranged resumable download remains the required download path.
- Limit each file to 5 GiB and each user to 20 GiB of active stored files for the first performance release.
- Delete existing FXM1 test data instead of building a legacy migration or compatibility path.
- Defer E2EE, Android Keystore file-key wrapping, encrypted sharing, and cryptographic wire formats to a future OpenSpec change.
- Preserve HTTPS, authentication, authorization, ownership checks, input limits, and private storage.

## Capabilities

### New Capabilities

- `high-performance-file-transfer`: streaming, multipart, resumable, resource-bounded file transfer with measurable performance behavior.

### Modified Capabilities

- `encrypted-file-transfer`: remove application-layer file encryption requirements from the active target architecture while retaining endpoint validation and transfer lifecycle behavior.
- `file-transfer-tickets`: generalize the direct data plane from encrypted objects to ordinary file objects and support multipart transfer tickets.

## Impact

- Android: transfer repository, local Room state, background execution, direct stream/range I/O, progress and cancellation UI.
- Backend: multipart session orchestration, presigned part URLs, reconciliation, complete/abort operations, metadata persistence and cleanup.
- MinIO: private bucket, incomplete multipart lifecycle cleanup, checksums and ranged downloads.
- Specifications: cryptographic documents remain future research and are not implementation requirements for this change.
