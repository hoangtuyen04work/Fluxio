# Tasks

## 0. Complete implementation-ready specification

- [x] 0.1 Define the target OpenAPI surface, DTOs, status codes, ownership behavior, idempotency, and upload/resume sequence in `contracts/openapi.yaml` and `contracts/api-contract.md`.
- [x] 0.2 Define PostgreSQL and Android Room data models, quota invariants, indexes, transaction boundaries, and recovery behavior in `contracts/data-model.md`.
- [x] 0.3 Define explicit server and Android transfer state-transition tables, including allowed commands, failure recovery, and terminal-state behavior in `contracts/state-machines.md`.
- [x] 0.4 Define the emulator benchmark protocol and numeric demonstration gates for Good Wi-Fi throughput, completion rate, memory, CPU, recovery, and retry cost in `contracts/benchmark-protocol.md`; explicitly exclude battery and physical-device certification.
- [ ] 0.5 Build a requirement-to-contract-to-test traceability matrix and obtain final design sign-off before implementation. Matrix: `contracts/traceability-matrix.md`; project-owner approval pending.

## 1. Resolve release boundaries

- [x] 1.1 Set the maximum file size to 5 GiB and quota to 20 GiB per user; record them in the delta spec with initialization and reservation-release scenarios.
- [x] 1.2 Delete FXM1 test data rather than supporting legacy compatibility; update the design and specs so no behavior-changing question remains.

## 2. Control-plane transfer foundation

- [ ] 2.1 Define PostgreSQL file and multipart-session migrations with idempotency, expiry, 5 GiB validation, and atomic 20 GiB quota reservation constraints; verify migration tests apply and roll back cleanly.
- [ ] 2.2 Implement create, sign-parts, list-parts, complete, and abort APIs; verify contract tests cover ownership, invalid state, duplicate completion, expiry, and ordered consecutive parts.
- [ ] 2.3 Add incomplete-upload cleanup and verify expired MinIO multipart uploads and database sessions are both removed.

## 3. Android streaming upload

- [ ] 3.1 Add Room transfer/part state and persisted URI handling; verify state survives process recreation.
- [ ] 3.2 Implement bounded multipart streaming without whole-file cache; verify peak memory remains bounded for files larger than the application heap.
- [ ] 3.3 Implement reconciliation, retry, cancellation, and duplicate-work prevention; verify an interrupted upload sends only missing parts after restart.

## 4. Android resumable download

- [ ] 4.1 Implement ranged download continuation guarded by object version; verify matching versions resume and changed versions restart safely.
- [ ] 4.2 Add foreground progress and cancellation behavior; verify transferred bytes, total bytes, state, retry, and cancellation are visible to the user.

## 5. Benchmark and select defaults

- [ ] 5.1 Build the repeatable fixed-emulator benchmark harness for 10 MiB, 100 MiB, 1 GiB, and 5 GiB files under Good Wi-Fi and Interrupted profiles; verify it reports throughput where applicable, completion rate, peak memory, CPU, recovery time, and retry cost.
- [ ] 5.2 Measure part sizes 5/8/16 MiB with concurrency 1/2/4, select defaults from evidence, and record the selected values and rationale in the spec and design.
- [ ] 5.3 Run end-to-end upload, resume, complete, download, cancel, and cleanup validation against MinIO; verify all OpenSpec scenarios have corresponding evidence.

## Workflow follow-up

- Archive the change only after implementation, benchmark acceptance, and canonical spec synchronization are complete.
