# Fluxio Member 2 Multipart Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Spring Boot multipart control plane and Android bounded multipart uploader for the mandatory B2 and B3 benchmark modes.

**Architecture:** Spring Boot remains a modular monolith and uses PostgreSQL as the authority for ownership, quota, lifecycle, and idempotency. An S3-compatible AWS SDK v2 adapter controls MinIO multipart uploads and signs direct Android-to-MinIO part requests; Android plans ranges and sends only bounded part bodies, reconciling against MinIO before resume.

**Tech Stack:** Java 21, Spring Boot 4, Spring JDBC, Flyway, PostgreSQL, AWS SDK v2 S3/Presigner, Testcontainers, Kotlin, Retrofit, OkHttp, coroutines.

**Spec:** `openspec/changes/optimize-transfer-performance/design.md`, `contracts/openapi.yaml`, `contracts/api-contract.md`, `contracts/data-model.md`, `contracts/state-machines.md`, and `specs/high-performance-file-transfer/spec.md`.

## Global Constraints

- All `app/...` paths and `./gradlew` commands in this plan are relative to the `Fluxio-Android/` project directory.
- Maximum file size is exactly `5,368,709,120` bytes (5 GiB); reject larger files before storage initialization.
- Per-owner quota is exactly `21,474,836,480` bytes (20 GiB), counting active bytes plus reservations.
- Presigned part URLs expire after 15 minutes; never persist or log them.
- A part URL request contains at most 32 unique, in-range part numbers.
- Upload sessions expire after 24 hours without successful activity; `COMPLETING` sessions unresolved for two minutes are reconciled, never blindly aborted.
- Owner-scoped missing and foreign resources both return `404`.
- `Idempotency-Key` is unique by `(owner, operation, key)`; reuse with a different canonical request hash returns `409`.
- Completion accepts consecutive ordered parts starting at 1, matching MinIO ETags and the declared byte total.
- Backend never proxies file bodies; Android transfers bytes directly to MinIO.
- Application-layer encryption and FXM1 compatibility are excluded.

## Review Focus

- Two concurrent upload initializations near quota must serialize so at most one reservation succeeds; `UploadApplicationServiceConcurrencyIT` pins this behavior.
- A repeated create/complete request after a lost response must return the original result without repeating quota or storage effects; idempotency integration tests pin both paths.
- A crash after MinIO completion but before database finalization must reconcile the deterministic object and finalize exactly once; `UploadCompletionReconciliationIT` pins this gap.
- A stale Android Room part marked complete but absent in MinIO must be uploaded again; `MultipartUploadEngineTest` uses server parts as authoritative.
- Cancellation racing with completion must yield `409 UPLOAD_COMPLETION_IN_PROGRESS` and never release a reservation prematurely; `UploadLifecycleIT` pins the race.

---

## File Map

- `fluxio/src/main/resources/db/migration/V1__transfer_schema.sql`: transfer metadata, quota, and idempotency schema.
- `fluxio/src/main/java/com/example/fluxio/transfer/domain/*`: lifecycle values and immutable domain records.
- `fluxio/src/main/java/com/example/fluxio/transfer/persistence/*`: explicit JDBC repositories and transaction-scoped quota transitions.
- `fluxio/src/main/java/com/example/fluxio/transfer/storage/*`: S3-compatible multipart and presigning boundary.
- `fluxio/src/main/java/com/example/fluxio/transfer/application/*`: create, sign, list, complete, abort, expire, and reconcile use cases.
- `fluxio/src/main/java/com/example/fluxio/transfer/api/*`: OpenAPI DTO mapping and problem responses.
- `app/src/main/java/com/example/fluxio/data/remote/*`: Retrofit transfer contract and DTOs.
- `app/src/main/java/com/example/fluxio/transfer/upload/*`: part planning and bounded B2/B3 execution.

### Task 1: PostgreSQL schema and atomic quota reservation

**Files:**
- Modify: `fluxio/pom.xml`
- Create: `fluxio/src/main/resources/db/migration/V1__transfer_schema.sql`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/domain/UploadState.java`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/persistence/UploadRepository.java`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/persistence/JdbcUploadRepository.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/persistence/UploadRepositoryIT.java`

**Interfaces:**
- Consumes: authenticated `ownerId`, declared size, deterministic upload/file identifiers.
- Produces: `reserveAndCreateInitializing(CreateUploadRecord command) -> UploadSessionRecord`; `activate(UUID uploadId, String storageUploadId, Instant expiresAt)`; terminal quota conversion/release methods guarded by state.

- [ ] **Step 1: Write failing Testcontainers tests** proving the 5 GiB boundary, 20 GiB quota, concurrent reservation serialization, schema constraints, and exactly-once terminal accounting.
- [ ] **Step 2: Run `mvn -Dtest=UploadRepositoryIT test` from `fluxio`** and verify failure because the migration/repository does not exist.
- [ ] **Step 3: Add Spring JDBC, Flyway, PostgreSQL, and Testcontainers dependencies** and implement the migration with the exact constraints and indexes from `data-model.md`.
- [ ] **Step 4: Implement the JDBC repository** using `SELECT ... FOR UPDATE` (creating the usage row when absent) and conditional state updates for release/conversion.
- [ ] **Step 5: Re-run `mvn -Dtest=UploadRepositoryIT test`** and verify all repository tests pass.
- [ ] **Step 6: Commit** with `git commit -m "feat(server): add transfer schema and atomic quota reservation"`.

### Task 2: S3-compatible multipart storage boundary

**Files:**
- Modify: `fluxio/pom.xml`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/storage/MultipartObjectStore.java`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/storage/S3MultipartObjectStore.java`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/storage/StorageProperties.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/storage/S3MultipartObjectStoreIT.java`

**Interfaces:**
- Consumes: deterministic `objectKey`, `storageUploadId`, part number, ordered completed parts.
- Produces: `createMultipart`, `presignUploadPart`, `listParts`, `complete`, `abort`, and `headObject`; all return domain values without exposing SDK types.

- [ ] **Step 1: Write failing MinIO-container integration tests** for create, signed direct PUT, authoritative list, ordered completion, abort, and deterministic object head/version metadata.
- [ ] **Step 2: Run `mvn -Dtest=S3MultipartObjectStoreIT test`** and verify failure because the boundary is missing.
- [ ] **Step 3: Replace direct MinIO SDK usage for the new path with AWS SDK v2 S3 client and presigner** configured for path-style access and the existing private MinIO endpoint; keep the legacy controller untouched until cutover.
- [ ] **Step 4: Implement `MultipartObjectStore`** with bounded SDK timeouts, no nested unbounded retries, checksum/ETag preservation, and no URL logging.
- [ ] **Step 5: Re-run the integration test** and verify all multipart operations pass against MinIO.
- [ ] **Step 6: Commit** with `git commit -m "feat(server): add S3 multipart storage adapter"`.

### Task 3: Create, sign, and list-parts application services and API

**Files:**
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/application/UploadApplicationService.java`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/application/IdempotencyService.java`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/api/UploadController.java`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/api/TransferDtos.java`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/api/TransferProblemHandler.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/api/UploadControllerIT.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/application/UploadApplicationServiceConcurrencyIT.java`

**Interfaces:**
- Consumes: the exact JSON/header contract in `contracts/openapi.yaml` and the repositories/storage boundary from Tasks 1–2.
- Produces: `POST /api/v1/uploads`, `POST /api/v1/uploads/{uploadId}/part-urls`, and `GET /api/v1/uploads/{uploadId}/parts`.

- [ ] **Step 1: Write failing HTTP and service tests** for validation, ownership concealment, idempotent create, request-hash conflict, max 32 distinct part numbers, invalid ranges, URL expiry, and authoritative list parts.
- [ ] **Step 2: Run `mvn -Dtest=UploadControllerIT,UploadApplicationServiceConcurrencyIT test`** and verify the endpoints/services are missing.
- [ ] **Step 3: Implement canonical request hashing and idempotency persistence** with replay of the stored status/body and `409` on hash mismatch.
- [ ] **Step 4: Implement create compensation** so a storage-init failure aborts any created multipart upload and releases the reservation exactly once, while uncertain initialization remains discoverable.
- [ ] **Step 5: Implement controller/DTO/problem mapping** exactly from OpenAPI, including stable `application/problem+json` codes and no signed URL leakage.
- [ ] **Step 6: Run the focused tests** and verify all pass.
- [ ] **Step 7: Commit** with `git commit -m "feat(server): expose multipart create sign and list APIs"`.

### Task 4: Completion, abort, expiry, and recovery

**Files:**
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/application/UploadApplicationService.java`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/application/UploadReconciliationService.java`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/application/UploadCleanupJob.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/api/UploadController.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/application/UploadLifecycleIT.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/application/UploadCompletionReconciliationIT.java`

**Interfaces:**
- Consumes: ordered `CompletedPart(partNumber, etag, checksumSha256)` and server/storage session state.
- Produces: idempotent `complete(ownerId, uploadId, key, parts) -> FileMetadata`, `abort(ownerId, uploadId)`, bounded expiry cleanup, and reconciliation for `INITIALIZING`/`COMPLETING`.

- [ ] **Step 1: Write failing lifecycle tests** for ordered/consecutive parts, wrong ETag/byte total, repeat completion, abort repeat, abort-during-completion conflict, 24-hour expiry, two-minute completion reconciliation, and crash-gap recovery.
- [ ] **Step 2: Run the focused lifecycle tests** and verify failure at missing behavior.
- [ ] **Step 3: Implement the `ACTIVE -> COMPLETING -> COMPLETED` recovery protocol** and atomically create the file plus convert quota after storage completion.
- [ ] **Step 4: Implement conditional abort/expiry and bounded scheduled batches** so only the state-transition winner calls storage and changes counters.
- [ ] **Step 5: Implement reconciliation** using deterministic object key and object metadata before deciding final state.
- [ ] **Step 6: Re-run lifecycle tests** and verify all pass.
- [ ] **Step 7: Commit** with `git commit -m "feat(server): complete abort and reconcile multipart uploads"`.

### Task 5: Android transfer API and deterministic part planner

**Files:**
- Create: `app/src/main/java/com/example/fluxio/data/remote/TransferApi.kt`
- Create: `app/src/main/java/com/example/fluxio/data/remote/TransferDtos.kt`
- Create: `app/src/main/java/com/example/fluxio/transfer/upload/ChunkPlanner.kt`
- Test: `app/src/test/java/com/example/fluxio/transfer/upload/ChunkPlannerTest.kt`
- Test: `app/src/test/java/com/example/fluxio/data/remote/TransferContractTest.kt`

**Interfaces:**
- Consumes: `RangeSource` from Member 1 and OpenAPI request/response fields.
- Produces: `ChunkPlanner.plan(totalBytes: Long, partSizeBytes: Long): List<PartRange>` where parts are one-based and only the final range may be short; Retrofit methods for all upload endpoints.

- [ ] **Step 1: Write failing tests** for zero/negative inputs, exact boundary, final short part, 5 GiB arithmetic without integer overflow, DTO field names, problem parsing, and required headers.
- [ ] **Step 2: Run `./gradlew testDebugUnitTest --tests "*ChunkPlannerTest" --tests "*TransferContractTest"`** and verify failure because types are missing.
- [ ] **Step 3: Implement DTOs, Retrofit declarations, and `ChunkPlanner`** using `Long` byte offsets/sizes and no stored signed URLs.
- [ ] **Step 4: Re-run the focused Android tests** and verify they pass.
- [ ] **Step 5: Commit** with `git commit -m "feat(android): add multipart API and chunk planner"`.

### Task 6: Bounded B2/B3 multipart upload engine

**Files:**
- Create: `app/src/main/java/com/example/fluxio/transfer/upload/MultipartUploadEngine.kt`
- Create: `app/src/main/java/com/example/fluxio/transfer/upload/PartUploader.kt`
- Create: `app/src/main/java/com/example/fluxio/transfer/upload/UploadRetryPolicy.kt`
- Test: `app/src/test/java/com/example/fluxio/transfer/upload/MultipartUploadEngineTest.kt`
- Test: `app/src/test/java/com/example/fluxio/transfer/upload/UploadRetryPolicyTest.kt`

**Interfaces:**
- Consumes: `RangeSource.open(offset, length)`, `TransferStore`, `TransferApi`, `ChunkPlanner`, and configured `maxConcurrency` of 1 (B2) or 2/4 (B3).
- Produces: `UploadEngine.run(localTransferId: UUID): UploadResult`; it reconciles server parts first, requests URLs in batches of at most 32, and persists confirmed ETag/checksum before completion.

- [ ] **Step 1: Write failing coroutine tests** proving the concurrency ceiling, server-authoritative missing set, per-part retry only, expired URL refresh, `Retry-After`, pause stops new work, cancellation enters `CANCELLING`, and confirmed parts are not resent after restart.
- [ ] **Step 2: Run the focused engine tests** and verify failure because the engine is missing.
- [ ] **Step 3: Implement `PartUploader`** as a streaming OkHttp request body over exactly one source range with cancellation propagation and no whole-part/full-file duplicate buffer.
- [ ] **Step 4: Implement `MultipartUploadEngine`** with structured concurrency/semaphore bounding, capped exponential backoff with jitter for `408/429/5xx`, reconciliation, ordered completion, and Room updates only after durable confirmation.
- [ ] **Step 5: Re-run engine tests** and verify they pass for B2 and B3 configurations.
- [ ] **Step 6: Commit** with `git commit -m "feat(android): add bounded resumable multipart upload"`.

### Task 7: End-to-end multipart evidence

**Files:**
- Create: `fluxio/src/test/java/com/example/fluxio/transfer/MultipartUploadE2EIT.java`
- Create: `docs/evidence/multipart-upload.md`

**Interfaces:**
- Consumes: Tasks 1–6 plus Member 1 range streaming and Member 4 transfer persistence.
- Produces: reproducible evidence that B2/B3 bytes, checksums, state, and quota match after normal completion, interruption/resume, and cancellation.

- [ ] **Step 1: Add an end-to-end test** that uploads a multi-part fixture directly through signed URLs, restarts/reconciles midway, completes it, downloads/verifies SHA-256, and asserts quota counters.
- [ ] **Step 2: Run `mvn -Dtest=MultipartUploadE2EIT test` and the focused Android engine suite** and verify they pass.
- [ ] **Step 3: Record commands, environment, and results** in `docs/evidence/multipart-upload.md`, explicitly noting that benchmark acceptance remains Member 4 work.
- [ ] **Step 4: Commit** with `git commit -m "test: verify multipart upload lifecycle end to end"`.
