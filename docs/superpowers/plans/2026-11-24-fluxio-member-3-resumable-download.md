# Fluxio Member 3 Resumable Download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build direct sequential streaming download with version-guarded ranged resume, correct cancellation cleanup, and an optional segmented-download experiment.

**Architecture:** Spring Boot authorizes ownership and issues a short-lived ranged-download ticket containing immutable object identity metadata; file bytes remain direct from MinIO to Android. Android writes sequentially to a seekable destination, persists only durable byte progress, and resumes only when the remote object version matches the partial file's recorded version.

**Tech Stack:** Java 21, Spring Boot 4, AWS SDK v2 S3 Presigner, Kotlin, Retrofit, OkHttp, coroutines, Android Storage Access Framework, Room.

**Spec:** `openspec/changes/optimize-transfer-performance/design.md`, `contracts/openapi.yaml`, `contracts/api-contract.md`, `contracts/data-model.md`, `contracts/state-machines.md`, and `specs/high-performance-file-transfer/spec.md`.

## Global Constraints

- All `app/...` paths and `./gradlew` commands in this plan are relative to the `Fluxio-Android/` project directory.
- Backend never proxies file bytes and never exposes another owner's file existence.
- Download URLs expire after 15 minutes and are neither persisted nor logged.
- Required release path is sequential streaming plus version-guarded HTTP range continuation.
- Resume is allowed only when the returned object version/ETag matches the stored partial-download version.
- Durable progress means bytes successfully written to the destination, not bytes merely received from the network.
- Cancellation deletes the partial output or remains `CANCELLING` until cleanup succeeds.
- Maximum supported file size is 5 GiB, so all offsets and lengths use 64-bit values.
- Parallel segmented download is optional and cannot block or replace the required sequential path.
- Application-layer encryption and FXM1 decoding are excluded.

## Review Focus

- A provider that cannot seek/truncate reliably must not append incompatible bytes; `SafDownloadDestinationTest` pins capability detection and restart behavior.
- A server that ignores a non-zero `Range` and returns `200` must trigger a safe restart, never append the full body at the offset; `SequentialDownloadEngineTest` pins this.
- A version change between ticket acquisition and resume must discard/truncate partial bytes and inform state before restarting at zero; the engine test pins this.
- A process death after network receipt but before durable flush must resume from the last persisted durable offset; destination/engine tests pin progress ordering.
- Cancellation cleanup failure must retain `CANCELLING` and be retryable after restart; `DownloadCancellationRecoveryTest` pins recovery.

---

## File Map

- `fluxio/src/main/java/com/example/fluxio/file/api/DownloadTicketController.java`: authenticated ticket endpoint.
- `fluxio/src/main/java/com/example/fluxio/file/application/DownloadTicketService.java`: ownership and storage metadata lookup.
- `app/src/main/java/com/example/fluxio/data/remote/DownloadApi.kt`: ticket contract.
- `app/src/main/java/com/example/fluxio/transfer/download/DownloadDestination.kt`: seek/truncate/delete abstraction.
- `app/src/main/java/com/example/fluxio/transfer/download/SequentialDownloadEngine.kt`: required streaming/range path.
- `app/src/main/java/com/example/fluxio/transfer/download/SegmentedDownloadEngine.kt`: optional experiment isolated behind the same engine interface.

### Task 1: Download ticket control plane

**Files:**
- Create: `fluxio/src/main/java/com/example/fluxio/file/application/DownloadTicketService.java`
- Create: `fluxio/src/main/java/com/example/fluxio/file/api/DownloadTicketController.java`
- Create: `fluxio/src/main/java/com/example/fluxio/file/api/DownloadTicketDtos.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/storage/MultipartObjectStore.java`
- Test: `fluxio/src/test/java/com/example/fluxio/file/api/DownloadTicketControllerIT.java`

**Interfaces:**
- Consumes: authenticated `ownerId`, active `fileId`, stored object key/version/size, and `presignGet(objectKey, expiresAt)`.
- Produces: `POST /api/v1/files/{fileId}/download-ticket` matching `contracts/openapi.yaml`, including signed URL, expiry, size, content type, object version, ETag, and range capability metadata.

- [ ] **Step 1: Write failing HTTP tests** for owner success, foreign/missing `404`, deleted file `404`, URL expiry metadata, and response fields with no URL logging.
- [ ] **Step 2: Run `mvn -Dtest=DownloadTicketControllerIT test`** and verify failure because the endpoint is absent.
- [ ] **Step 3: Add ranged GET presigning to the storage boundary** without introducing SDK types into application/controller code.
- [ ] **Step 4: Implement the ticket service/controller** from the OpenAPI contract, using active PostgreSQL metadata as the ownership authority.
- [ ] **Step 5: Re-run the focused test** and verify all cases pass.
- [ ] **Step 6: Commit** with `git commit -m "feat(server): issue owner-scoped download tickets"`.

### Task 2: Android download contract and seekable destination

**Files:**
- Create: `app/src/main/java/com/example/fluxio/data/remote/DownloadApi.kt`
- Create: `app/src/main/java/com/example/fluxio/data/remote/DownloadDtos.kt`
- Create: `app/src/main/java/com/example/fluxio/transfer/download/DownloadDestination.kt`
- Create: `app/src/main/java/com/example/fluxio/transfer/download/SafDownloadDestination.kt`
- Test: `app/src/test/java/com/example/fluxio/transfer/download/SafDownloadDestinationTest.kt`
- Test: `app/src/test/java/com/example/fluxio/data/remote/DownloadContractTest.kt`

**Interfaces:**
- Consumes: destination `content://` URI and ticket JSON.
- Produces: `DownloadDestination.openAt(offset: Long)`, `truncate(size: Long)`, `durablePosition(): Long`, `delete(): Boolean`, and Retrofit `createDownloadTicket(fileId)`.

- [ ] **Step 1: Write failing tests** for 64-bit seek, truncate-to-zero, durable-position reporting, delete failure, non-seekable provider detection, DTO field names, and problem parsing.
- [ ] **Step 2: Run `./gradlew testDebugUnitTest --tests "*SafDownloadDestinationTest" --tests "*DownloadContractTest"`** and verify missing types.
- [ ] **Step 3: Implement the destination abstraction** using `ParcelFileDescriptor`/seekable channel when supported; expose inability to seek/truncate as a typed result that forces restart rather than unsafe append.
- [ ] **Step 4: Implement Retrofit DTOs/API** without persisting the signed URL.
- [ ] **Step 5: Re-run the focused tests** and verify they pass.
- [ ] **Step 6: Commit** with `git commit -m "feat(android): add download ticket and seekable destination"`.

### Task 3: Required sequential streaming download

**Files:**
- Create: `app/src/main/java/com/example/fluxio/transfer/download/DownloadEngine.kt`
- Create: `app/src/main/java/com/example/fluxio/transfer/download/SequentialDownloadEngine.kt`
- Create: `app/src/main/java/com/example/fluxio/transfer/download/DownloadRetryPolicy.kt`
- Test: `app/src/test/java/com/example/fluxio/transfer/download/SequentialDownloadEngineTest.kt`

**Interfaces:**
- Consumes: `TransferStore`, `DownloadApi`, OkHttp, `DownloadDestination`, expected SHA-256 when available.
- Produces: `DownloadEngine.run(localTransferId: UUID): DownloadResult`; sequentially streams a `200` response from zero or a validated `206 Content-Range` response from the durable offset.

- [ ] **Step 1: Write failing MockWebServer/coroutine tests** for zero-offset streaming, valid `206` continuation, ignored range returning `200`, malformed `Content-Range`, short body, `416`, capped transient retry, signed URL refresh, and bounded fixed-buffer memory.
- [ ] **Step 2: Run the focused engine test** and verify failure because the engine is missing.
- [ ] **Step 3: Implement sequential streaming** with a fixed reusable buffer, cancellation propagation, explicit response validation, and progress persistence only after destination writes/flush checkpoints.
- [ ] **Step 4: Implement retry policy** for `408`, `429`, and `5xx`, honoring `Retry-After`; do not retry other contract/auth/state `4xx` responses except refreshed `401` authorization.
- [ ] **Step 5: Re-run the focused tests** and verify all pass.
- [ ] **Step 6: Commit** with `git commit -m "feat(android): stream sequential downloads with range support"`.

### Task 4: Version-guarded resume and cancellation recovery

**Files:**
- Modify: `app/src/main/java/com/example/fluxio/transfer/download/SequentialDownloadEngine.kt`
- Create: `app/src/main/java/com/example/fluxio/transfer/download/DownloadRecovery.kt`
- Test: `app/src/test/java/com/example/fluxio/transfer/download/DownloadRecoveryTest.kt`
- Test: `app/src/test/java/com/example/fluxio/transfer/download/DownloadCancellationRecoveryTest.kt`

**Interfaces:**
- Consumes: persisted object version, durable offset, current ticket version, Android transfer state machine.
- Produces: `reconcile(transfer, ticket, destination): ResumeDecision` with exact decisions `RESUME`, `RESTART_VERSION_CHANGED`, `RESTART_DESTINATION_UNSEEKABLE`, or `REQUIRES_RESELECTION`.

- [ ] **Step 1: Write failing tests** proving matching-version resume, changed-version truncate/restart and user-visible reason, missing URI permission, cancel success, cancel cleanup failure, and process restart while `CANCELLING`.
- [ ] **Step 2: Run the focused recovery tests** and verify missing behavior.
- [ ] **Step 3: Implement reconciliation before every non-zero range** and ensure no response bytes are written until version and range semantics are accepted.
- [ ] **Step 4: Implement cancellation recovery** that closes active calls, attempts partial deletion, marks `CANCELLED` only after confirmed cleanup, and retries cleanup from persisted `CANCELLING`.
- [ ] **Step 5: Re-run recovery and sequential-engine tests** and verify all pass.
- [ ] **Step 6: Commit** with `git commit -m "feat(android): add version guarded download recovery"`.

### Task 5: Download integration evidence

**Files:**
- Create: `app/src/androidTest/java/com/example/fluxio/transfer/download/ResumableDownloadInstrumentedTest.kt`
- Create: `docs/evidence/resumable-download.md`

**Interfaces:**
- Consumes: Tasks 1–4 and Member 4 Room/WorkManager integration.
- Produces: reproducible evidence for correct bytes, durable progress, interruption recovery, version change, and cancellation cleanup.

- [ ] **Step 1: Add an instrumented test fixture** that downloads at least 100 MiB, interrupts midway, recreates the worker/process-facing components, resumes, and verifies final size/SHA-256 without rewriting the confirmed prefix.
- [ ] **Step 2: Add version-change and cancellation cases** that prove partial data is never mixed and cleanup reaches the correct terminal state.
- [ ] **Step 3: Run `./gradlew connectedDebugAndroidTest` on the fixed emulator** and verify the required download cases pass.
- [ ] **Step 4: Record the emulator, commands, and results** in `docs/evidence/resumable-download.md` without claiming physical-device or battery certification.
- [ ] **Step 5: Commit** with `git commit -m "test: verify resumable download on emulator"`.

### Task 6: Optional segmented-download experiment

**Files:**
- Create: `app/src/main/java/com/example/fluxio/transfer/download/SegmentedDownloadEngine.kt`
- Test: `app/src/test/java/com/example/fluxio/transfer/download/SegmentedDownloadEngineTest.kt`
- Create: `docs/evidence/segmented-download.md`

**Interfaces:**
- Consumes: the same `DownloadEngine`, ticket version, seekable destination, and bounded concurrency configuration.
- Produces: an opt-in experimental engine that writes disjoint validated ranges at exact offsets and performs final size/SHA-256 validation; it is never the required default.

- [ ] **Step 1: Begin only after Tasks 1–5 and required benchmark gates pass; otherwise mark this task omitted** in the evidence as allowed by the approved spec.
- [ ] **Step 2: If proceeding, write failing tests** for non-overlapping complete range coverage, concurrency ceiling, exact offset writes, version consistency, per-segment retry, and final checksum failure cleanup.
- [ ] **Step 3: Implement the minimal segmented engine behind an explicit experiment flag** without changing sequential resume behavior.
- [ ] **Step 4: Run the segmented tests and fixed-emulator comparison**; record whether it improves Good Wi-Fi throughput and its CPU/RAM cost.
- [ ] **Step 5: Commit** with `git commit -m "experiment(android): evaluate segmented download"` only if implemented.
