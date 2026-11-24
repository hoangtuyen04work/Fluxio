# Requirement Traceability Matrix

> Change: `optimize-transfer-performance`  
> Specification status: `APPROVED`  
> Implementation status: not started  
> Last audit: 2026-11-24

## Status model

- `SPECIFIED`: requirement, design, implementation task, and planned evidence are defined.
- `IMPLEMENTED`: code and migrations exist but acceptance evidence is incomplete.
- `VERIFIED`: planned automated tests or benchmark evidence pass.
- `BLOCKED`: implementation or verification cannot proceed without a decision or dependency.

All active rows are currently `SPECIFIED`. Removed requirements remain traceable so implementation and cleanup can prove they are absent.

## Active requirement matrix

| ID | Requirement / scenario | Contract and design coverage | Implementation task | Planned test or evidence | Status |
|---|---|---|---|---|---|
| `HPFT-001` | Valid file at or below 5 GiB and within 20 GiB quota creates a reservation | [`POST /uploads`](./openapi.yaml), [`ACCOUNT_STORAGE_USAGE`](./data-model.md) | `2.1`, `2.2` | Contract/integration test asserts `201`, one session, and exact reserved bytes | `SPECIFIED` |
| `HPFT-002` | File greater than 5 GiB is rejected before storage work | `CreateUploadRequest.maximum`, size invariant | `2.1`, `2.2` | Test 5 GiB + 1 byte returns `413`, with no session, reservation, or MinIO upload | `SPECIFIED` |
| `HPFT-003` | Upload that would exceed 20 GiB quota is rejected | `422 QuotaExceeded`, locked quota row | `2.1`, `2.2` | Concurrent requests near quota allow only the valid winner; counters remain within limit | `SPECIFIED` |
| `HPFT-004` | Reservation converts or releases exactly once | Quota transaction boundaries, terminal state transitions | `2.1`–`2.3` | Complete/abort/expiry retries change counters once and never make them negative | `SPECIFIED` |
| `HPFT-005` | `INITIALIZING` holds quota until activation or abort | Reservation ownership invariant, server state table | `2.1`, `2.3` | MinIO init failure/retry keeps reservation; five-minute terminal cleanup releases once | `SPECIFIED` |
| `HPFT-006` | Large upload streams without whole-file copy | Direct multipart design, Room part model | `3.1`, `3.2` | 5 GiB emulator test completes with no full temp copy and within memory gate | `SPECIFIED` |
| `HPFT-007` | In-flight part count is bounded | Candidate concurrency values and Android execution design | `3.2` | Instrumented test observes no more than configured 1/2/4 concurrent part requests | `SPECIFIED` |
| `HPFT-008` | Restart reconciles and sends only missing parts | `GET /uploads/{id}/parts`, `RECONCILING` state | `3.1`, `3.3` | Kill/recreate app after confirmed parts; no confirmed ETag/part is retransmitted | `SPECIFIED` |
| `HPFT-009` | Lost URI permission requires user reselection | Room URI rule, `REQUIRES_RESELECTION` transition | `3.1`, `3.3` | Revoke permission and recreate process; no read retry occurs before reselection | `SPECIFIED` |
| `HPFT-010` | Inactive `ACTIVE` upload expires after 24 hours | Server time policy and `ACTIVE → EXPIRED` transition | `2.3` | Clock-controlled cleanup aborts MinIO, releases quota once, later work returns `410` | `SPECIFIED` |
| `HPFT-011` | Stale `COMPLETING` session is reconciled, not expired | Two-minute reconciliation threshold | `2.2`, `2.3` | Simulate crash after MinIO completion; recovery creates one file and correct quota | `SPECIFIED` |
| `HPFT-012` | Abort during completion returns conflict | `DELETE /uploads/{id}` and state-specific `409` | `2.2` | Concurrent complete/abort returns `UPLOAD_COMPLETION_IN_PROGRESS`; one proven outcome wins | `SPECIFIED` |
| `HPFT-013` | Pause stops new upload work but retains session | Android pause semantics | `3.3`, `4.2` | Pause stops scheduling; resume reconciles before sending and does not abort server session | `SPECIFIED` |
| `HPFT-014` | Cancellation survives process recreation | `CANCELLING` recovery table | `3.3`, `4.2` | Kill/recreate during cancel; state stays non-terminal until cleanup confirmation | `SPECIFIED` |
| `HPFT-015` | Completion uses ordered confirmed parts; abandoned sessions clean up | Complete DTO, completion validation, cleanup state transitions | `2.2`, `2.3` | Reject gaps/order/ETag/size mismatch; valid duplicate completion returns one file | `SPECIFIED` |
| `HPFT-016` | Matching object version resumes ranged download | `POST /files/{id}/download-ticket`, Room object version | `4.1` | Interrupt download; matching version resumes from durable byte offset | `SPECIFIED` |
| `HPFT-017` | Changed object version restarts from byte zero | Download version reconciliation rule | `4.1` | Change version during partial download; partial bytes are replaced, never appended | `SPECIFIED` |
| `HPFT-018` | Cancelled download removes partial file | Android cancellation semantics | `4.2` | Cancel removes partial output or stays `CANCELLING` until deferred cleanup passes | `SPECIFIED` |
| `HPFT-019` | Progress, retry, state, and confirmed cancellation are observable | Room `transfers`, Android state definitions | `3.1`, `3.3`, `4.2` | UI/instrumentation observes confirmed bytes and state; upload reaches `CANCELLED` only after abort/expiry | `SPECIFIED` |
| `HPFT-020` | Demonstration defaults are selected from fixed-emulator evidence | [`benchmark-protocol.md`](./benchmark-protocol.md) candidate matrix | `5.1`, `5.2` | Raw CSV/JSON plus summary covers all candidates and selection rule | `SPECIFIED` |
| `HPFT-021` | Good Wi-Fi median throughput reaches 75% for files at least 100 MiB | Good Wi-Fi numeric gate | `5.1`, `5.2` | Five measured runs per acceptance size; median directional payload throughput passes | `SPECIFIED` |
| `HPFT-022` | Interrupted transfers resume in all ten acceptance runs | Interrupted profile, retry/reconciliation gates | `5.1`–`5.3` | Two 30-second outages; 10/10 per size complete with retry bytes within bound | `SPECIFIED` |
| `HPFT-023` | Emulator report states its evidence boundary | Benchmark environment/evidence format | `5.1`–`5.3` | Report records host/emulator fingerprint and excludes battery/physical-device claims | `SPECIFIED` |
| `HPFT-024` | Mandatory `B0`–`B4` modes attribute each optimization layer | Comparative baseline section of [`benchmark-protocol.md`](./benchmark-protocol.md) | `5.1`, `5.2` | Raw/summary results compare startup, throughput, heap, CPU, temporary storage, retry, and recovery within each mode's required scope | `SPECIFIED` |
| `HPFT-025` | `B0` resource failure is recorded rather than hidden | Baseline exemption and evidence rules | `5.1` | Oversized baseline attempt records failure/resource use without changing optimized-path gates | `SPECIFIED` |
| `HPFT-026` | Optional segmented download does not block required ranged resume | Optional experiment boundary in design and benchmark protocol | `4.1`, optional research after `5.3` | Required range/resume tests pass whether or not segmented mode exists; optional results are reported separately | `SPECIFIED` |
| `FTT-001` | File bytes move directly between Android and private MinIO | Presigned URLs, control/data-plane boundary | `2.2`, `3.2`, `4.1` | Network integration evidence shows backend handles JSON only, never file bodies | `SPECIFIED` |
| `FTT-002` | Owner requests short-lived URLs only for valid missing parts | `POST /uploads/{id}/part-urls`, max 32 unique numbers | `2.2`, `3.3` | Ownership/range/state tests return `200`, `400`, `404`, `409`, or `410` as specified | `SPECIFIED` |
| `FTT-003` | Consecutive confirmed parts complete exactly one file | Complete endpoint, idempotency record, `COMPLETING` recovery | `2.1`, `2.2` | Parallel/repeated complete calls return same `fileId`, one row, one quota conversion | `SPECIFIED` |
| `EFT-001` | Successful upload persists file ID and completed transfer locally | `FileMetadata`, Room `transfers` | `3.1`, `3.3` | Process recreation after completion retains remote file ID and `COMPLETED` state | `SPECIFIED` |
| `EFT-002` | Duplicate local execution reuses existing work | Room work identity uniqueness, no intentional cross-device handoff | `3.1`, `3.3` | Start same action twice; one worker runs and existing progress is foregrounded | `SPECIFIED` |

## Removed requirement matrix

| ID | Removed behavior | Replacement / cleanup decision | Implementation task | Planned evidence | Status |
|---|---|---|---|---|---|
| `REM-001` | Authenticated local encryption | Ordinary bytes over authorized TLS/presigned transfers; encryption deferred | `3.2`, `4.1` | No FXM1 encryption step or whole-file encrypted temp copy in active transfer path | `SPECIFIED` |
| `REM-002` | FXM1 compatibility | Delete test data; no legacy reader or migration | `1.2`, pre-release cleanup | Bucket/data inspection shows no retained FXM1 objects; code has no compatibility branch | `SPECIFIED` |
| `REM-003` | GCM verification before destination write | Object checksum and version validation only; no authenticity claim | `4.1`, `5.3` | Checksum/version tests pass and product text makes no cryptographic-authenticity claim | `SPECIFIED` |
| `REM-004` | Installation-bound restore | Authorized devices may access new ordinary objects | `2.2`, `4.1` | Ownership authorization permits another valid session/device without Keystore unwrap | `SPECIFIED` |

## Endpoint coverage

| OpenAPI operation | Requirement IDs | Ownership/state/error coverage |
|---|---|---|
| `createUpload` | `HPFT-001`–`HPFT-005` | JWT, idempotency, `400`, `409`, `413`, `422` |
| `signUploadParts` | `HPFT-007`, `FTT-001`, `FTT-002` | Owner-hidden `404`; invalid part `400`; invalid/expired state `409/410` |
| `listUploadedParts` | `HPFT-008`, `HPFT-011`, `FTT-002` | MinIO authoritative; owner-hidden `404`; invalid/expired state `409/410` |
| `completeUpload` | `HPFT-004`, `HPFT-011`, `HPFT-012`, `HPFT-015`, `FTT-003`, `EFT-001` | Ordered validation, idempotency, recovery, state conflict |
| `abortUpload` | `HPFT-004`, `HPFT-010`, `HPFT-012`, `HPFT-014`, `HPFT-019` | Repeated terminal abort `204`; completion conflict `409` |
| `createDownloadTicket` | `HPFT-016`–`HPFT-018`, `FTT-001`, `REM-003`, `REM-004` | JWT, owner-hidden `404`, object version and ranged URL |

No OpenAPI operation is orphaned from an active requirement.

## Audit findings

### Resolved during this audit

1. Added missing `400 BadRequest` to `signUploadParts` for invalid part requests.
2. Added missing `409 InvalidUploadState` to `listUploadedParts` so OpenAPI matches the state table.
3. Generalized abort `409` description to include completion and other non-abortable states.
4. Corrected upload cancellation wording: Android enters `CANCELLING` and becomes `CANCELLED` only after abort or expiry confirmation.
5. Confirmed `INITIALIZING`, `ACTIVE`, and `COMPLETING` all retain quota until a proven terminal transition.

### Known, accepted limitations

- Performance evidence is emulator-only and cannot certify physical-device CPU, battery, radio, thermal, or production behavior.
- Cross-device handoff of an active upload is not intentionally supported.
- Client-side encryption and FXM1 compatibility are deferred/removed from this change.
- Exact demonstration part size and concurrency remain unset until tasks `5.1` and `5.2` produce evidence.
- Segmented parallel download is optional and cannot delay or replace required ranged-resume evidence.

### Blocking gaps

No unresolved behavior-changing specification gap was found. Implementation and verification remain pending by design.

## Final design sign-off

- [x] Every active requirement and scenario has a stable trace ID.
- [x] Every active scenario maps to contract/design coverage.
- [x] Every active scenario maps to an implementation task.
- [x] Every active scenario has planned automated or benchmark evidence.
- [x] Every OpenAPI operation maps back to at least one requirement.
- [x] API, data model, state machine, and benchmark protocol were audited for conflicts.
- [x] Emulator-only demonstration scope and deferred production validation are explicit.
- [x] `openspec validate --all` passes.
- [x] Project owner approves this design for implementation.

**Decision:** `APPROVED`  
**Approver:** Project owner  
**Approval date:** 2026-11-24

Approval completes specification task `0.5`. It does not mark implementation or verification rows complete; those advance from `SPECIFIED` only when their code and evidence exist.

