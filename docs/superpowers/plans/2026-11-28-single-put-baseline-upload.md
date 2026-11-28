# Single-PUT Baseline Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a user-selectable B1 direct single-PUT upload path that shares Fluxio's quota, metadata, WorkManager, and measurement infrastructure with optimized multipart upload.

**Architecture:** Spring Boot exposes dedicated `/api/v1/single-uploads` control-plane endpoints but reuses the existing upload-session and stored-file persistence boundaries with a strategy discriminator. Android persists the chosen strategy in Room, executes both modes through WorkManager, and implements B1 as fixed-buffer URI streaming with restart-from-zero retry and `AUTH_REQUIRED` recovery.

**Tech Stack:** Java 21, Spring Boot 4, PostgreSQL/Flyway, AWS S3 SDK/MinIO, Kotlin 2.2, Android SDK 36, Room, WorkManager, OkHttp, JUnit, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-28-single-put-baseline-upload-design.md`

## Global Constraints

- Maximum file size is 5 GiB and total active plus reserved quota is 20 GiB per owner.
- File bodies travel directly between Android and private MinIO; Spring Boot never proxies the body.
- B1 reads a trustworthy exact-size content URI through bounded buffering, creates no whole-file cache copy, and never resumes a partial PUT.
- B1 has five total persisted PUT attempts; a new attempt starts at byte zero and records resent bytes.
- Authentication remains memory-only; missing/expired JWT transitions to `AUTH_REQUIRED` until user sign-in.
- B1 and optimized upload use the same unencrypted source bytes, object store, metadata, quota, scheduler, and measurement recorder.

## Review Focus

- A PUT that finishes near expiry must become either one completed file or a deleted late object, never an orphan with released quota.
- Duplicate create/complete/abort calls must preserve exactly-once quota accounting.
- Unknown-size or no-longer-readable URIs must fail before network transfer with stable user-visible state.
- Authentication failure must not consume the five-attempt transfer budget or create a WorkManager retry loop.
- Cancellation must close the source/request promptly and converge backend object, session, quota, and Room state.

---

### Task 1: Persist single-PUT strategy and lifecycle

**Files:**
- Create: `fluxio/src/main/resources/db/migration/V3__single_put_upload_strategy.sql`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/persistence/CreateUploadRecord.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/persistence/UploadSessionRecord.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/persistence/UploadRepository.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/persistence/JdbcUploadRepository.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/persistence/UploadRepositoryIT.java`

**Interfaces:**
- Produces: `UploadStrategy { MULTIPART, SINGLE_PUT }`; strategy-aware session creation; exact-once finalization/abort accounting shared by later services.

- [ ] **Step 1: Write failing repository integration tests** for V3 migration, valid strategy-dependent nullability, concurrent quota reservation, repeated finalization, and repeated abort.
- [ ] **Step 2: Run the repository integration test** with `cd fluxio; .\mvnw.cmd -Dtest=UploadRepositoryIT test` and confirm the new cases fail because strategy persistence is absent.
- [ ] **Step 3: Add the V3 migration and `UploadStrategy` persistence contract**, keeping multipart rows valid while allowing `storage_upload_id`, `part_size_bytes`, and `part_count` to be absent only for `SINGLE_PUT`.
- [ ] **Step 4: Implement repository mappings and conditional updates** so both strategies share quota and `stored_file` finalization without double accounting.
- [ ] **Step 5: Re-run `UploadRepositoryIT`** and confirm all cases pass.
- [ ] **Step 6: Commit** with `git commit -m "feat(server): persist single put upload sessions"`.

### Task 2: Implement the single-upload control plane

**Files:**
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/application/SingleUploadApplicationService.java`
- Create: `fluxio/src/main/java/com/example/fluxio/transfer/api/SingleUploadController.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/api/TransferDtos.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/storage/MultipartObjectStore.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/storage/S3MultipartObjectStore.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/TransferConfiguration.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/application/SingleUploadApplicationServiceTest.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/api/SingleUploadControllerTest.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/storage/S3MultipartObjectStoreIT.java`

**Interfaces:**
- Consumes: strategy-aware repository operations from Task 1.
- Produces: create, refresh URL, complete, and abort endpoints matching `contracts/openapi.yaml`; object-store methods `presignPut(String objectKey, String contentType)` and `deleteObject(String objectKey)`.

- [ ] **Step 1: Write failing service/controller tests** for ownership, size/quota rejection, idempotent create, URL refresh, exact-size HEAD completion, duplicate completion, and abort.
- [ ] **Step 2: Run targeted tests** with `cd fluxio; .\mvnw.cmd -Dtest=SingleUploadApplicationServiceTest,SingleUploadControllerTest test` and confirm missing types/endpoints fail.
- [ ] **Step 3: Extend the object-store boundary** with whole-object presigning and deletion, and cover PUT/HEAD/delete against MinIO in `S3MultipartObjectStoreIT`.
- [ ] **Step 4: Implement `SingleUploadApplicationService`** with signatures `create(ownerId, request)`, `refreshUrl(ownerId, uploadId)`, `complete(ownerId, uploadId)`, and `abort(ownerId, uploadId)` using the existing idempotency/problem conventions.
- [ ] **Step 5: Implement `SingleUploadController` and DTO mappings** exactly as the OpenAPI contract specifies.
- [ ] **Step 6: Run all backend tests** with `cd fluxio; .\mvnw.cmd test` and confirm `BUILD SUCCESS`.
- [ ] **Step 7: Commit** with `git commit -m "feat(server): add single put upload api"`.

### Task 3: Reconcile expiry and late single-PUT objects

**Files:**
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/application/UploadCleanupJob.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/application/UploadReconciliationService.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/persistence/UploadRepository.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/persistence/JdbcUploadRepository.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/application/UploadLifecycleServiceTest.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/application/UploadRecoveryServiceTest.java`

**Interfaces:**
- Consumes: single-upload session/object-store operations from Tasks 1–2.
- Produces: bounded reconciliation that promotes exact-size objects and removes mismatched, absent, or late objects without quota drift.

- [ ] **Step 1: Add failing lifecycle tests** for exact-size object at expiry, mismatched object, absent object, and an object appearing after the first abort/expiry cleanup pass.
- [ ] **Step 2: Run the two lifecycle test classes** and confirm the B1 cases fail.
- [ ] **Step 3: Implement strategy-aware cleanup/reconciliation** using conditional claims and a bounded follow-up deletion check; never speculate while a claimed completion outcome is unknown.
- [ ] **Step 4: Re-run lifecycle tests and the full backend suite** and confirm all pass.
- [ ] **Step 5: Commit** with `git commit -m "feat(server): reconcile abandoned single uploads"`.

### Task 4: Implement Android B1 streaming and durable state

**Files:**
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/upload/SinglePutUploadEngine.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/upload/StreamingPutBody.kt`
- Modify: `Fluxio-Android/app/src/main/java/com/example/fluxio/data/remote/TransferApi.kt`
- Modify: `Fluxio-Android/app/src/main/java/com/example/fluxio/data/remote/TransferDtos.kt`
- Modify: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/persistence/TransferEntities.kt`
- Modify: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/persistence/TransferDao.kt`
- Modify: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/persistence/TransferDatabase.kt`
- Modify: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/work/TransferWork.kt`
- Test: `Fluxio-Android/app/src/test/java/com/example/fluxio/transfer/upload/SinglePutUploadEngineTest.kt`
- Test: `Fluxio-Android/app/src/test/java/com/example/fluxio/transfer/upload/StreamingPutBodyTest.kt`

**Interfaces:**
- Produces: `UploadStrategy.SINGLE_PUT`; `AUTH_REQUIRED`; a five-attempt restart-from-zero engine; fixed-buffer OkHttp request body with progress/cancellation and redundant-byte reporting.

- [ ] **Step 1: Write failing tests** for exact-byte streaming without a temp file, cancellation closing the source, retryable statuses, five-attempt persistence, restart from offset zero, URL refresh, `AUTH_REQUIRED`, and unknown source size.
- [ ] **Step 2: Run targeted Android tests** with `cd Fluxio-Android; .\gradlew.bat testDebugUnitTest --tests "com.example.fluxio.transfer.upload.*SinglePut*"` and confirm failures.
- [ ] **Step 3: Add Room strategy/state fields and migration**, preserving existing transfer rows as multipart/default implementation-state data.
- [ ] **Step 4: Add Retrofit DTOs/endpoints** matching `SingleUploadSession` and `SinglePutTicket`.
- [ ] **Step 5: Implement `StreamingPutBody` and `SinglePutUploadEngine.run(localTransferId: UUID)`** with bounded I/O, five total attempts, byte-zero retry, authentication pause, progress, cancellation, complete, and abort.
- [ ] **Step 6: Connect the executor dependency used by `TransferWorker`** so persisted strategy dispatches to B1 or multipart rather than returning failure when `TransferDependencies.executor` is unset.
- [ ] **Step 7: Run `cd Fluxio-Android; .\gradlew.bat testDebugUnitTest assembleDebug`** and confirm both tasks succeed.
- [ ] **Step 8: Commit** with `git commit -m "feat(android): add durable single put upload"`.

### Task 5: Add UI selection and comparable evidence

**Files:**
- Modify: `Fluxio-Android/app/src/main/java/com/example/fluxio/MainActivity.kt`
- Modify: `Fluxio-Android/app/src/main/java/com/example/fluxio/benchmark/BenchmarkHarness.kt`
- Test: `Fluxio-Android/app/src/test/java/com/example/fluxio/benchmark/BenchmarkHarnessTest.kt`
- Test: `Fluxio-Android/app/src/androidTest/java/com/example/fluxio/ExampleInstrumentedTest.kt`
- Modify: `docs/testing/TEST-SCENARIOS-AND-RESULTS.md`
- Modify: `openspec/changes/optimize-transfer-performance/contracts/traceability-matrix.md`
- Modify: `openspec/changes/optimize-transfer-performance/progress.md`

**Interfaces:**
- Consumes: both runnable upload strategies and shared measurement fields.
- Produces: normal/optimized selector, sign-in recovery action, same-file comparison records, and E2E evidence procedure.

- [ ] **Step 1: Add failing UI/benchmark tests** asserting strategy labels, strategy persistence, B1/B4 mode attribution, resent-byte accounting, and sign-in recovery behavior.
- [ ] **Step 2: Run unit/instrumentation tests** and confirm new assertions fail before UI wiring.
- [ ] **Step 3: Replace direct `FluxioClient.upload()` use** with transfer creation/scheduling and present the two approved labels: normal single PUT and optimized multipart/resume.
- [ ] **Step 4: Extend the recorder and test checklist** so both modes emit the same metric schema and use the same file/environment fingerprint.
- [ ] **Step 5: Run backend, Android, OpenSpec, and E2E verification**; record actual evidence without marking benchmark gates passed when emulator measurements are absent.
- [ ] **Step 6: Update traceability/progress only from observed results**, leaving tasks 5.2/5.3 open until the required runs exist.
- [ ] **Step 7: Commit** with `git commit -m "feat(android): compare normal and optimized uploads"`.

## Final verification

- [ ] Run `cd fluxio; .\mvnw.cmd test` and confirm `BUILD SUCCESS`.
- [ ] Run `cd Fluxio-Android; .\gradlew.bat testDebugUnitTest assembleDebug` and confirm `BUILD SUCCESSFUL`.
- [ ] Run `openspec validate --all` and confirm zero failures.
- [ ] Run the documented same-file B1/B4 E2E scenarios against PostgreSQL and MinIO and attach raw evidence.
- [ ] Confirm `git status --short` contains only intentional source, specification, plan, and evidence changes.
