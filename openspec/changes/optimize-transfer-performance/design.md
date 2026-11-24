# Design

## Context

The current Android implementation encrypts a complete file into app cache and performs one PUT. The selected target removes client-side encryption from this branch and replaces whole-file buffering with direct multipart streaming. See `proposal.md` for motivation and scope.

## Goals / Non-Goals

**Goals:**

- Maximize practical upload/download throughput while bounding memory and temporary storage.
- Resume after network interruption or process death without retransmitting confirmed parts.
- Keep file bytes off the Spring Boot backend by preserving the direct Android/MinIO data plane.
- Produce fixed-emulator benchmark evidence for part size, concurrency, retry, memory, CPU, and reliability choices while clearly excluding battery and physical-device certification.
- Provide a foundation for file catalog, management, and sharing.

**Non-Goals:**

- Client-side encryption, FXM1, E2EE, key recovery, or encrypted sharing.
- Microservices, message brokers, distributed caches, or Kubernetes-specific design.
- Malware scanning or content transformation in this change.

## Decisions

### Contract-first control plane

The target HTTP surface is defined by [`contracts/openapi.yaml`](./contracts/openapi.yaml). Contract semantics, retry behavior, idempotency, and the upload sequence are defined in [`contracts/api-contract.md`](./contracts/api-contract.md). PostgreSQL and Android Room ownership, invariants, and transaction boundaries are defined in [`contracts/data-model.md`](./contracts/data-model.md). Normative server and Android lifecycle transitions are defined in [`contracts/state-machines.md`](./contracts/state-machines.md).

These documents are normative for this change. The current `/api/v1/mvp/files` endpoints describe the implementation before this change and are not retained as a compatibility requirement.

### Modular monolith control plane

Keep Spring Boot as one deployable with modules for identity, file catalog, transfer orchestration, sharing, and audit. PostgreSQL owns metadata and MinIO owns file bytes. This minimizes network hops and operational overhead while preserving internal boundaries.

Alternative considered: microservices. Rejected until measured load, team boundaries, or independent scaling requirements justify the cost.

### Direct multipart data plane

The backend creates MinIO multipart uploads and signs individual part operations. Android streams source ranges directly to MinIO and records confirmed part number, size, checksum, and ETag in Room. The backend never proxies file bytes.

Alternative considered: backend-proxied streaming. Rejected because it doubles bandwidth through the control plane and creates a scaling bottleneck.

### Bounded concurrency with measured defaults

Concurrency and part size are configuration values. Initial benchmark candidates are part sizes 5, 8, and 16 MiB with concurrency 1, 2, and 4. These are experiment inputs, not defaults. Demonstration defaults are selected only after fixed-emulator benchmark evidence; production certification remains deferred.

For this demonstration change, the normative protocol is [`contracts/benchmark-protocol.md`](./contracts/benchmark-protocol.md). It uses one fixed Android Studio Emulator, Good Wi-Fi and Interrupted profiles, and applies the throughput gate only to Good Wi-Fi. Physical-device, battery, radio, and thermal validation are deferred; therefore selected values are demonstration defaults rather than production-certified defaults.

### Mandatory comparative baseline

The evaluation MUST compare five modes defined by the benchmark protocol: `B0` whole-file staging and single PUT, `B1` direct fixed-buffer streaming and single PUT, `B2` sequential multipart, `B3` bounded parallel multipart, and `B4` the complete asynchronous/resumable solution. Application-layer encryption is disabled in every benchmark mode so the measurements isolate transfer architecture rather than cryptographic cost.

`B0` is an intentionally unoptimized research control and is not required to pass the release memory, large-file, or recovery gates. A resource failure at a larger size is recorded as a baseline result rather than hidden or treated as a defect in the optimized path.

### Optional segmented download experiment

Sequential ranged download with version-guarded resume is required. Parallel segmented-range download is optional research owned by the download work package. If implemented, it MUST preserve object-version consistency, bounded concurrency, correct offset writes, and final checksum validation. Failure to implement or outperform sequential ranged download does not block the demonstration release.

### Resume by authoritative reconciliation

Room stores local intent and progress. MinIO `ListParts` through the backend is authoritative for uploaded parts. Resume computes the missing set and obtains new short-lived URLs. Completion is idempotent and uses consecutive ordered part numbers.

### Download continuation by object version

Downloads persist byte count, destination information, and object version/ETag. Range continuation is allowed only when the remote version matches; otherwise the partial download is discarded or restarted.

### Android execution model

User-started large transfers run as user-visible foreground work with cancellation. WorkManager coordinates retry/reconciliation and deferred work. The design must account for modern Android background and job quotas rather than assuming an unlimited long-running worker.

### Correctness without application encryption

TLS protects transport. MinIO/S3 checksums validate transferred bytes where supported. The specification does not claim end-to-end encryption or protection from a trusted-storage compromise.

### Release storage limits

The first performance release accepts files up to 5 GiB and provides a 20 GiB quota per user. Upload initialization reserves the declared file size atomically so concurrent sessions cannot oversubscribe quota. Completion converts the reservation to active file usage; abort, cancellation, and expiry release it exactly once.

These are product limits, not multipart protocol limits. They can be changed later through an OpenSpec capability change backed by storage and performance evidence.

Quota accounting uses one owner-scoped usage row. Upload initialization atomically adds a reservation; successful completion atomically moves the same bytes from reserved to active usage; abort and expiry release the reservation through an idempotent terminal transition.

### Legacy FXM1 policy

All existing FXM1 objects are non-production test data. They are deleted before enabling the new transfer path. The application does not retain a legacy FXM1 reader, dual-format transfer flow, or migration job in this release.

## Risks / Trade-offs

- [Files are stored without application-layer encryption] → private bucket, strict authorization, TLS, limited presigned URL lifetime, and explicit future encryption change.
- [Content URI cannot be reopened after process death] → request persistable read permission where supported and mark non-reopenable transfers as requiring user reselection.
- [Parallelism increases memory and CPU use, while physical-device battery/radio cost remains unknown] → bounded configuration, emulator benchmark gates, and explicit deferral of physical-device certification.
- [Presigned URL leakage grants temporary access] → short lifetime, operation/part scoping, HTTPS, and no URL logging.
- [Incomplete multipart uploads consume storage] → explicit abort plus scheduled expiry cleanup.
- [Legacy FXM1 test objects are unreadable after the switch] → delete the test objects before release and verify the bucket contains no retained FXM1 data.

## Migration Plan

1. Delete existing FXM1 test objects and verify no production data depends on the legacy format.
2. Implement multipart control-plane APIs, 5 GiB file validation, atomic 20 GiB quota reservation, and PostgreSQL transfer/file metadata.
3. Implement Room transfer state and streaming multipart upload.
4. Implement ranged resumable download and user-visible foreground execution.
5. Benchmark candidate settings and select defaults.
6. Switch all new uploads to the performance path.

Rollback keeps the existing single-PUT FXM1 path available until multipart integration and benchmark acceptance pass.
