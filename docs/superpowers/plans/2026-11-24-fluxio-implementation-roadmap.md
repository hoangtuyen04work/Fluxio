# Fluxio Performance Transfer Roadmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the FXM1 whole-file MVP transfer path with a measured Android streaming, multipart, asynchronous, resumable upload/download demonstration while preserving a mandatory `B0`–`B4` comparison.

**Architecture:** Spring Boot remains a modular-monolith control plane backed by PostgreSQL and an S3-compatible MinIO data plane. Android owns streaming I/O, Room transfer state, WorkManager orchestration, progress, retry, and benchmark collection; file bodies never pass through Spring Boot.

**Tech Stack:** Java 21, Spring Boot 4, Spring JDBC, Flyway, PostgreSQL, AWS SDK v2 S3 compatibility for MinIO, Kotlin 2.2, Android SDK 36/minSdk 26, OkHttp, Coroutines, Room, WorkManager, Compose, JUnit, MockWebServer, Testcontainers.

**Spec:** [`openspec/changes/optimize-transfer-performance/`](../../../openspec/changes/optimize-transfer-performance/)

## Global Constraints

- Maximum file size is exactly 5 GiB (`5,368,709,120` bytes); account quota is exactly 20 GiB (`21,474,836,480` bytes).
- `INITIALIZING`, `ACTIVE`, and `COMPLETING` reserve quota; terminal effects occur exactly once.
- File bodies transfer directly between Android and private MinIO through 15-minute presigned URLs.
- Upload sessions have a five-minute initialization deadline and 24-hour active inactivity lifetime; stale completion reconciliation starts after two minutes.
- Android never persists presigned URLs and treats MinIO/server reconciliation as authoritative.
- Memory is bounded by buffer/part/concurrency, not file size; no optimized mode creates a whole-file temporary copy.
- Mandatory benchmark modes are `B0`–`B4`; encryption is disabled in every benchmark mode.
- Segmented parallel download is optional and may start only after required sequential ranged resume passes.
- Physical-device battery, radio, thermal, and production performance certification are out of scope.
- Follow TDD for every implementation task and keep OpenSpec/progress/traceability synchronized with verified evidence.

## Review Focus

- Two concurrent create-upload requests near quota must not both reserve beyond 20 GiB; covered by Member 2 backend persistence tests.
- A crash after MinIO complete but before PostgreSQL finalization must recover one file and one quota conversion; covered by Member 2 completion tests.
- Android process death with stale Room parts must reconcile before sending bytes and never resend confirmed parts; covered by Member 4 recovery tests and Member 2 upload tests.
- URI permission loss must stop retry and enter `REQUIRES_RESELECTION`; covered by Member 1 URI tests and Member 4 state tests.
- Download version changes must never append new-version bytes to old partial data; covered by Member 3 ranged-download tests.

---

## Plan Set

| Order | Plan | Primary owner | Independently testable output |
|---:|---|---|---|
| 1 | [`2026-11-24-fluxio-member-1-streaming.md`](./2026-11-24-fluxio-member-1-streaming.md) | Thành viên 1 | Bounded range source, checksum, `B0`/`B1` transfer primitives |
| 2 | [`2026-11-24-fluxio-member-4-async-benchmark.md`](./2026-11-24-fluxio-member-4-async-benchmark.md) tasks 1–3 | Thành viên 4 | Room/state/repository contracts consumed by upload/download |
| 3 | [`2026-11-24-fluxio-member-2-multipart-upload.md`](./2026-11-24-fluxio-member-2-multipart-upload.md) | Thành viên 2 | PostgreSQL/MinIO control plane and Android `B2`/`B3` upload |
| 4 | [`2026-11-24-fluxio-member-3-resumable-download.md`](./2026-11-24-fluxio-member-3-resumable-download.md) | Thành viên 3 | Version-guarded ranged streaming download |
| 5 | [`2026-11-24-fluxio-member-4-async-benchmark.md`](./2026-11-24-fluxio-member-4-async-benchmark.md) remaining tasks | Thành viên 4 | WorkManager `B4`, UI state, benchmark runner and reports |

Member 1 may work in parallel with Member 2's backend-only tasks. After the shared Android interfaces land, Member 2 Android upload and Member 3 download may proceed in parallel. Member 4 integrates only reviewed public interfaces and owns final end-to-end verification.

### Vietnamese companion notes

The English implementation plans above remain authoritative. These companion files explain the work in Vietnamese for team discussion:

- [Member 1 — Streaming note](./2026-11-24-fluxio-member-1-streaming-vi.md)
- [Member 2 — Multipart upload note](./2026-11-24-fluxio-member-2-multipart-upload-vi.md)
- [Member 3 — Resumable download note](./2026-11-24-fluxio-member-3-resumable-download-vi.md)
- [Member 4 — Async and benchmark note](./2026-11-24-fluxio-member-4-async-benchmark-vi.md)

## Locked Android Interfaces

These interfaces are the integration boundary; changes require all affected owners to update their plans/tests and OpenSpec contracts.

```kotlin
interface RangeSource {
    val sizeBytes: Long
    suspend fun copyRange(offset: Long, length: Long, sink: BufferedSink): Long
}

interface TransferStore {
    suspend fun create(transfer: TransferRecord)
    suspend fun get(localId: String): TransferRecord?
    suspend fun transition(localId: String, expected: Set<TransferState>, next: TransferState): Boolean
    suspend fun replaceRemoteParts(localId: String, parts: List<ConfirmedPart>)
    fun observe(localId: String): Flow<TransferRecord?>
}

interface UploadEngine {
    suspend fun run(localId: String): UploadOutcome
    suspend fun reconcile(localId: String): UploadReconciliation
    suspend fun requestCancel(localId: String): CancelOutcome
}

interface DownloadEngine {
    suspend fun run(localId: String): DownloadOutcome
    suspend fun reconcile(localId: String): DownloadReconciliation
    suspend fun removePartial(localId: String): Boolean
}
```

## Integration Gates

### Gate A — Shared foundations

- [ ] Member 1 range-source unit tests pass.
- [ ] Member 4 Room migration/DAO/state-transition tests pass.
- [ ] Public Android interfaces compile from both upload and download test fakes.

### Gate B — Control plane

- [ ] PostgreSQL migration applies cleanly to an empty database.
- [ ] Quota concurrency and idempotency tests pass.
- [ ] Create/sign/list/complete/abort contract tests match `contracts/openapi.yaml`.
- [ ] Completion crash recovery and expiry cleanup tests pass.

### Gate C — Android transfer paths

- [ ] `B1` streams without a full temporary copy.
- [ ] `B2` and `B3` upload only missing parts and obey concurrency.
- [ ] Required download resumes only when object version matches.
- [ ] Pause/cancel/process-recreation tests pass.

### Gate D — Evaluation

- [ ] `B0`–`B4` raw CSV/JSON exists for each mode's mandatory scope.
- [ ] Good Wi-Fi, interruption, memory, CPU, retry, quota, and correctness gates pass.
- [ ] Traceability rows advance from `SPECIFIED` to `IMPLEMENTED`/`VERIFIED` only with evidence links.
- [ ] Canonical specs are synchronized before archiving the OpenSpec change.

## Branch and Commit Discipline

- Create one integration branch after preserving the approved documentation state.
- Each task in the four detailed plans ends in one focused commit after its tests pass.
- Do not combine backend schema, Android Room schema, upload engine, and download engine in one commit.
- Rebase/merge shared-interface commits before implementing dependent tasks; do not copy interface definitions between branches.
- Never mark an OpenSpec task complete from code presence alone; run its stated verification first.

## Recommended Execution

Use subagent-driven execution if isolated worktrees/branches are available: the four work packages have distinct ownership and benefit from review at each interface gate. If implementing in one shared workspace, execute natively in roadmap order and serialize changes to shared files such as `libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`, `pom.xml`, and OpenSpec progress documents.

