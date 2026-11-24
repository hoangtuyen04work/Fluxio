# Fluxio Member 4 Async and Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist transfer state with Room, enforce the approved state machine through WorkManager/foreground work, and produce repeatable `B0`–`B4` benchmark evidence.

**Architecture:** Room is the device-local source for execution intent and confirmed local progress; server/MinIO reconciliation remains authoritative. Unique WorkManager names prevent duplicate workers, while upload/download engines remain injected interfaces owned by Members 2 and 3.

**Tech Stack:** Kotlin 2.2, Room 2.8.4 with KSP, WorkManager 2.11, Coroutines/Flow, Compose, Android instrumentation testing, WorkManager testing, Android Studio Profiler/Debug APIs, CSV/JSON output, ADB scripts.

**Spec:** [`state-machines.md`](../../../openspec/changes/optimize-transfer-performance/contracts/state-machines.md), [`benchmark-protocol.md`](../../../openspec/changes/optimize-transfer-performance/contracts/benchmark-protocol.md).

## Global Constraints

- Persisted URLs are forbidden; store only server IDs, object versions, offsets, ETags/checksums, states, attempts, and timestamps.
- A transfer persisted as `RUNNING` after process death enters `RECONCILING` before any new bytes are sent.
- `CANCELLING` remains non-terminal until backend abort/expiry or local partial cleanup is confirmed.
- Only one non-terminal local worker may own a transfer identity.
- Progress counts confirmed upload parts or durable download bytes, never optimistic socket bytes.
- Benchmark reports include the fixed host/emulator fingerprint and explicitly exclude battery/physical-device claims.

## Review Focus

- Conditional state updates must reject stale workers.
- Room migration and uniqueness constraints must preserve terminal history while preventing duplicate active work.
- WorkManager retries must not multiply with engine retries into unbounded retry loops.
- Notification permission denial must not silently stop required foreground work; surface a stable error/action.
- Benchmark cleanup must reset MinIO/session/quota state without deleting non-test data.

---

### Task 1: Configure Room/KSP and define transfer models

**Files:**
- Modify: `Fluxio-Android/gradle/libs.versions.toml`
- Modify: `Fluxio-Android/build.gradle.kts`
- Modify: `Fluxio-Android/app/build.gradle.kts`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/model/TransferModels.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/db/TransferEntity.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/db/TransferPartEntity.kt`
- Test: `Fluxio-Android/app/src/test/java/com/example/fluxio/transfer/model/TransferStateTest.kt`

**Interfaces:**
- Produces: `TransferDirection`, `TransferState`, `PartState`, `TransferRecord`, `ConfirmedPart`, and Room entities matching `contracts/data-model.md`.

- [ ] **Step 1: Add KSP `2.2.20-2.0.4`, Room compiler/testing, WorkManager testing, and coroutine-test dependencies**

Keep existing Room/WorkManager versions. Configure `ksp(libs.androidx.room.compiler)` and Room schema export to `app/schemas`.

- [ ] **Step 2: Write failing state-model tests**

Assert terminal states are only `COMPLETED`/`CANCELLED`, server-expired upload produces non-retryable `FAILED`, and persisted `RUNNING`/`CANCELLING` map to recovery entry states.

- [ ] **Step 3: Run and confirm failure**

Run: `cd Fluxio-Android; .\gradlew.bat testDebugUnitTest --tests "com.example.fluxio.transfer.model.TransferStateTest"`

- [ ] **Step 4: Implement immutable models and Room entities**

Use string enum converters and `Long` byte counters. Add a deterministic `workIdentity` field used by the active-work unique index.

- [ ] **Step 5: Run tests and commit**

```powershell
git add Fluxio-Android/gradle/libs.versions.toml Fluxio-Android/build.gradle.kts Fluxio-Android/app/build.gradle.kts Fluxio-Android/app/schemas Fluxio-Android/app/src/main/java/com/example/fluxio/transfer Fluxio-Android/app/src/test/java/com/example/fluxio/transfer/model
git commit -m "feat(android): define durable transfer models"
```

### Task 2: Implement Room DAO and conditional state transitions

**Files:**
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/db/TransferDao.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/db/TransferPartDao.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/db/FluxioDatabase.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/db/RoomTransferStore.kt`
- Test: `Fluxio-Android/app/src/androidTest/java/com/example/fluxio/transfer/db/RoomTransferStoreTest.kt`

**Interfaces:**
- Produces the roadmap `TransferStore` interface and `RoomTransferStore` implementation.

- [ ] **Step 1: Write in-memory Room tests**

Cover create/observe, unique active work identity, stale transition rejection, confirmed-part replacement, confirmed-byte derivation, terminal-state immutability, and process-recovery mapping.

- [ ] **Step 2: Run and confirm failure**

Run: `cd Fluxio-Android; .\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.fluxio.transfer.db.RoomTransferStoreTest`

- [ ] **Step 3: Implement DAOs/database/store**

Conditional updates include `WHERE state IN (...)`; replace parts transactionally; derive upload progress from confirmed part sizes.

- [ ] **Step 4: Export/verify Room schema, run tests, commit**

```powershell
git add Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/db Fluxio-Android/app/src/androidTest/java/com/example/fluxio/transfer/db Fluxio-Android/app/schemas
git commit -m "feat(android): persist transfer state with room"
```

### Task 3: Implement scheduler, worker contracts, and foreground notification

**Files:**
- Modify: `Fluxio-Android/app/src/main/AndroidManifest.xml`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/work/TransferDependencies.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/work/TransferScheduler.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/work/UploadWorker.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/work/DownloadWorker.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/work/TransferNotification.kt`
- Test: `Fluxio-Android/app/src/androidTest/java/com/example/fluxio/transfer/work/TransferWorkerTest.kt`

**Interfaces:**
- Consumes: `TransferStore`; injects the roadmap `UploadEngine` and `DownloadEngine`.
- Produces: `enqueue(localId, direction)`, `pause(localId)`, `resume(localId)`, `cancel(localId)`.

- [ ] **Step 1: Add foreground/data-sync/notification manifest declarations**

Add only permissions/services required by current Android/WorkManager APIs; do not request storage-wide permissions.

- [ ] **Step 2: Write WorkManager tests with fake engines**

Assert unique work, `RUNNING → RECONCILING` recovery, retryable failure scheduling, non-retryable failure, pause, confirmed cancellation, and completion-winning-cancel race.

- [ ] **Step 3: Run and confirm failure**

Run the focused instrumentation class with `connectedDebugAndroidTest`.

- [ ] **Step 4: Implement workers and scheduler**

Use unique work name `transfer-<localId>`, foreground info for active large transfers, one WorkManager retry layer, and engine-owned bounded part retry.

- [ ] **Step 5: Run tests and commit**

```powershell
git add Fluxio-Android/app/src/main/AndroidManifest.xml Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/work Fluxio-Android/app/src/androidTest/java/com/example/fluxio/transfer/work
git commit -m "feat(android): orchestrate durable background transfers"
```

### Task 4: Expose transfer state in Compose

**Files:**
- Modify: `Fluxio-Android/app/src/main/java/com/example/fluxio/MainActivity.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/ui/TransferViewModel.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/ui/TransferScreen.kt`
- Test: `Fluxio-Android/app/src/androidTest/java/com/example/fluxio/transfer/ui/TransferScreenTest.kt`

**Interfaces:**
- Consumes: `TransferStore.observe` and `TransferScheduler` commands.
- Produces: UI for confirmed bytes/total, state, retry, pause/resume/cancel, and URI reselection.

- [ ] **Step 1: Write Compose UI tests**

Cover progress display, disabled duplicate start, pause/resume actions, `CANCELLING`, `REQUIRES_RESELECTION`, and terminal states.

- [ ] **Step 2: Run and confirm failure**

- [ ] **Step 3: Extract current login UI and implement transfer UI against Flow state**

Remove direct long-running transfer work from the Activity ViewModel; it only creates intents and schedules workers.

- [ ] **Step 4: Run UI tests and commit**

```powershell
git add Fluxio-Android/app/src/main/java/com/example/fluxio/MainActivity.kt Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/ui Fluxio-Android/app/src/androidTest/java/com/example/fluxio/transfer/ui
git commit -m "feat(android): show durable transfer progress and controls"
```

### Task 5: Build benchmark recorder and B0–B4 runner

**Files:**
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/benchmark/BenchmarkModels.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/benchmark/BenchmarkRecorder.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/benchmark/BenchmarkRunner.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/benchmark/BenchmarkExporter.kt`
- Create: `scripts/benchmark/interrupt-network.ps1`
- Create: `scripts/benchmark/summarize-results.ps1`
- Test: `Fluxio-Android/app/src/test/java/com/example/fluxio/benchmark/BenchmarkRecorderTest.kt`
- Test: `Fluxio-Android/app/src/androidTest/java/com/example/fluxio/benchmark/BenchmarkRunnerTest.kt`

**Interfaces:**
- Consumes: B0/B1 primitives, upload engine B2/B3, WorkManager path B4, download engine.
- Produces: one JSON/CSV row per `benchmark-protocol.md` evidence schema.

- [ ] **Step 1: Write recorder/exporter unit tests**

Assert environment fingerprint, mode, size, profile, part/concurrency, duration, throughput, heap, CPU, retry/redundant bytes, recovery timing, checksum, and result are mandatory and serialized deterministically.

- [ ] **Step 2: Run and confirm failure**

- [ ] **Step 3: Implement recorder/exporter using elapsed realtime, process CPU time, and sampled heap**

- [ ] **Step 4: Write runner instrumentation tests with fake modes**

Assert warm-up exclusion, randomized candidate order, cleanup between runs, B0 exemption, and no throughput gate on Interrupted.

- [ ] **Step 5: Implement runner and ADB interruption script**

The PowerShell script performs two 30-second connectivity outages, timestamps each transition, restores connectivity in `finally`, and refuses to run against a non-emulator serial.

- [ ] **Step 6: Run tests and commit**

```powershell
git add Fluxio-Android/app/src/main/java/com/example/fluxio/benchmark Fluxio-Android/app/src/test/java/com/example/fluxio/benchmark Fluxio-Android/app/src/androidTest/java/com/example/fluxio/benchmark scripts/benchmark
git commit -m "feat(benchmark): automate B0 through B4 measurements"
```

### Task 6: Run integration evidence and update traceability

**Files:**
- Create: `openspec/changes/optimize-transfer-performance/evidence/benchmark-summary.md`
- Create: `openspec/changes/optimize-transfer-performance/evidence/raw/.gitkeep`
- Modify: `openspec/changes/optimize-transfer-performance/contracts/benchmark-protocol.md`
- Modify: `openspec/changes/optimize-transfer-performance/contracts/traceability-matrix.md`
- Modify: `openspec/changes/optimize-transfer-performance/progress.md`
- Modify: `openspec/changes/optimize-transfer-performance/tasks.md`

**Interfaces:**
- Produces: selected part/concurrency defaults and VERIFIED evidence for benchmark/recovery rows.

- [ ] **Step 1: Run all Android unit/instrumentation tests and backend tests**

Expected: zero failures.

- [ ] **Step 2: Run mandatory B0–B4 matrix on the fixed emulator**

Store raw data outside Git if large; commit only the schema, checksums/manifest, and summary unless the course requires raw files in the repository.

- [ ] **Step 3: Apply all numeric gates and select defaults**

- [ ] **Step 4: Update benchmark protocol, traceability, progress, and tasks with evidence links**

- [ ] **Step 5: Validate OpenSpec and commit**

```powershell
openspec validate --all
git add openspec/changes/optimize-transfer-performance
git commit -m "docs: record transfer benchmark acceptance"
```

