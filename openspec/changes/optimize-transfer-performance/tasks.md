# Tasks

## 0. Complete implementation-ready specification

- [x] 0.1 Define the target OpenAPI surface, DTOs, status codes, ownership behavior, idempotency, and upload/resume sequence in `contracts/openapi.yaml` and `contracts/api-contract.md`.
- [x] 0.2 Define PostgreSQL and Android Room data models, quota invariants, indexes, transaction boundaries, and recovery behavior in `contracts/data-model.md`.
- [x] 0.3 Define explicit server and Android transfer state-transition tables, including allowed commands, failure recovery, and terminal-state behavior in `contracts/state-machines.md`.
- [x] 0.4 Define the emulator benchmark protocol and numeric demonstration gates for Good Wi-Fi throughput, completion rate, memory, CPU, recovery, and retry cost in `contracts/benchmark-protocol.md`; explicitly exclude battery and physical-device certification.
- [x] 0.5 Build a requirement-to-contract-to-test traceability matrix, include mandatory `B0`–`B4` baseline evidence and the optional segmented-download boundary, and obtain final design sign-off before implementation. Approved in `contracts/traceability-matrix.md` on 2026-11-24.

## 1. Resolve release boundaries

- [x] 1.1 Set the maximum file size to 5 GiB and quota to 20 GiB per user; record them in the delta spec with initialization and reservation-release scenarios.
- [x] 1.2 Delete FXM1 test data rather than supporting legacy compatibility; update the design and specs so no behavior-changing question remains.

## 2. Control-plane transfer foundation

- [x] 2.1 Define PostgreSQL file and multipart-session migrations with idempotency, expiry, 5 GiB validation, and atomic 20 GiB quota reservation constraints; verified by `UploadRepositoryIT` against PostgreSQL 17 on 2026-11-24.
- [x] 2.2 Implement create, sign-parts, list-parts, complete, and abort APIs; verified by controller, lifecycle, persistence, idempotency, PostgreSQL, and MinIO tests on 2026-11-24.
- [x] 2.3 Add incomplete-upload cleanup and crash-gap reconciliation; verified by bounded cleanup and recovery service tests on 2026-11-24.

## 3. Android streaming upload

- [ ] 3.1 Add Room transfer/part state and persisted URI handling; verify state survives process recreation.
- [x] 3.2 Implement bounded multipart streaming without whole-file cache; verified by 64 KiB request buffers, Long-safe 5 GiB chunk planning, and bounded-concurrency unit tests on 2026-11-24. Emulator peak-memory acceptance remains tracked by 5.1.
- [x] 3.3 Implement reconciliation, retry, cancellation, and duplicate-work prevention; verified by server-authoritative missing-part, per-part retry, cancellation-state, and concurrent-run tests on 2026-11-24.

## 4. Android resumable download

- [x] 4.0 Add owner-scoped, expiring download tickets backed by presigned object-store URLs; verified by controller and MinIO integration tests on 2026-11-24.
- [x] 4.1 Implement ranged download continuation guarded by object version; verified by matching-version resume, changed-version reset, cancellation, bounded-buffer streaming, and HTTP Range tests on 2026-11-24.
- [ ] 4.2 Add foreground progress and cancellation behavior; verify transferred bytes, total bytes, state, retry, and cancellation are visible to the user.

## 5. Benchmark and select defaults

- [ ] 5.1 Build the repeatable fixed-emulator benchmark harness with mandatory `B0`–`B4` modes for 10 MiB, 100 MiB, 1 GiB, and 5 GiB files under each mode's required Good Wi-Fi/Interrupted scope; verify it reports startup time, throughput where applicable, completion rate, peak memory, CPU, temporary-storage bytes, recovery time, and retry cost.
- [ ] 5.2 Measure part sizes 5/8/16 MiB with concurrency 1/2/4, select defaults from evidence, and record the selected values and rationale in the spec and design.
- [ ] 5.3 Run end-to-end upload, resume, complete, download, cancel, and cleanup validation against MinIO; verify all OpenSpec scenarios have corresponding evidence.

Optional research: implement and measure bounded parallel segmented-range download only after the required sequential ranged-resume path passes; omission does not block the change.

## Workflow follow-up

- Archive the change only after implementation, benchmark acceptance, and canonical spec synchronization are complete.
