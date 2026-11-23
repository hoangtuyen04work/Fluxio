# Transfer State Machines

## Scope

This document defines the normative server and Android state transitions for the first performance release. State changes MUST use conditional persistence updates so concurrent retries cannot apply quota, completion, or cancellation effects more than once.

## Server upload session

### State definitions

| State | Meaning | Holds reserved quota | Terminal |
|---|---|---:|---:|
| `INITIALIZING` | Quota is reserved and MinIO multipart creation is in progress | Yes | No |
| `ACTIVE` | The session may sign, upload, list, and reconcile parts | Yes | No |
| `COMPLETING` | Completion has been claimed and MinIO/database reconciliation is in progress | Yes | No |
| `COMPLETED` | The object and file metadata are complete; reservation became active usage | No | Yes |
| `ABORTED` | Initialization or upload was cancelled and reservation was released | No | Yes |
| `EXPIRED` | An inactive upload exceeded its lifetime and reservation was released | No | Yes |

### Transition table

| Current state | Event or command | Guard/action | Next state |
|---|---|---|---|
| none | Valid create request | Atomically reserve quota and insert session | `INITIALIZING` |
| `INITIALIZING` | MinIO multipart creation succeeds | Persist `storageUploadId`; return session only after commit | `ACTIVE` |
| `INITIALIZING` | Transient MinIO failure before five-minute deadline | Retry with bounded backoff | `INITIALIZING` |
| `INITIALIZING` | Five-minute deadline or permanent failure | Abort any discovered multipart upload and release quota once | `ABORTED` |
| `ACTIVE` | Sign parts or list parts | Validate owner and part range; successful activity refreshes inactivity deadline | `ACTIVE` |
| `ACTIVE` | Valid complete request | Claim completion using a conditional update | `COMPLETING` |
| `ACTIVE` | Abort request | Abort MinIO and release quota once | `ABORTED` |
| `ACTIVE` | No successful activity for 24 hours | Abort MinIO and release quota once | `EXPIRED` |
| `COMPLETING` | Reconciliation proves the object exists | Create/activate file, move reserved bytes to active bytes, store response | `COMPLETED` |
| `COMPLETING` | Reconciliation proves completion did not occur and cannot continue | Abort remaining multipart state and release quota once | `ABORTED` |
| `COMPLETING` | Completion outcome is temporarily unknown | Retry reconciliation; never expire or abort speculatively | `COMPLETING` |
| `COMPLETED` | Duplicate complete with matching idempotency request | Return the original `FileMetadata` | `COMPLETED` |
| `ABORTED` | Duplicate abort | Return `204` without changing counters | `ABORTED` |
| `EXPIRED` | Abort after cleanup | Return `204` without changing counters | `EXPIRED` |

No transition leaves `COMPLETED`, `ABORTED`, or `EXPIRED`. An abort request during `COMPLETING` is rejected with `409 UPLOAD_COMPLETION_IN_PROGRESS`; reconciliation decides the outcome.

### API behavior by state

| Operation | `INITIALIZING` | `ACTIVE` | `COMPLETING` | `COMPLETED` | `ABORTED` | `EXPIRED` |
|---|---|---|---|---|---|---|
| Return create result | `409` while initializing | `200/201` session | `409` | `200` original result when applicable | `409` | `410` |
| Sign part URLs | `409` | `200` | `409` | `409` | `409` | `410` |
| List parts | `409` | `200` | `200` for reconciliation | `409` | `409` | `410` |
| Complete | `409` | Claim and process | `409 UPLOAD_COMPLETION_IN_PROGRESS` or resume internally | `200` same file | `409` | `410` |
| Abort | `204` after cleanup | `204` after cleanup | `409 UPLOAD_COMPLETION_IN_PROGRESS` | `409` | `204` | `204` |

Owner mismatch and invisible resources return `404` before state-specific behavior. Invalid part numbers or malformed completion lists return `400`.

### Time policy

| Item | Locked value |
|---|---:|
| `INITIALIZING` maximum duration | 5 minutes |
| `ACTIVE` inactivity lifetime | 24 hours since last successful sign/list/upload reconciliation activity |
| Stale `COMPLETING` reconciliation threshold | 2 minutes |
| Presigned URL lifetime | 15 minutes |

`COMPLETING` does not expire after two minutes. It becomes eligible for immediate reconciliation and continues to hold quota until a proven terminal outcome.

## Android transfer

### State definitions

| State | Meaning | Terminal |
|---|---|---:|
| `QUEUED` | Waiting for foreground execution or WorkManager | No |
| `RUNNING` | Actively transferring bounded data | No |
| `PAUSED` | User-paused; partial data and server session are retained | No |
| `RECONCILING` | Comparing Room state with server/MinIO or validating a download version | No |
| `FAILED` | Transfer failed and records whether retry is allowed | No |
| `CANCELLING` | Local work stopped; cancellation/cleanup confirmation is pending | No |
| `REQUIRES_RESELECTION` | Android lost URI permission and needs user input | No |
| `COMPLETED` | Transfer completed successfully | Yes |
| `CANCELLED` | Cancellation and required cleanup were confirmed | Yes |

### Transition table

| Current state | Event | Action | Next state |
|---|---|---|---|
| `QUEUED` | Worker starts with usable URI | Begin bounded transfer | `RUNNING` |
| `QUEUED` | User cancels | Start abort or local cleanup | `CANCELLING` |
| `RUNNING` | User pauses | Stop scheduling new requests; retain partial state | `PAUSED` |
| `RUNNING` | Process restarts, response is uncertain, or network state must be checked | Persist progress and reconcile authoritative state | `RECONCILING` |
| `RUNNING` | Retryable failure | Store error and `retryable=true` | `FAILED` |
| `RUNNING` | Permanent failure | Store error and `retryable=false` | `FAILED` |
| `RUNNING` | User cancels | Stop local requests and request abort/cleanup | `CANCELLING` |
| `RUNNING` | Transfer is durably confirmed | Persist final metadata | `COMPLETED` |
| `PAUSED` | User resumes | Reconcile before sending more bytes | `RECONCILING` |
| `PAUSED` | User cancels | Request abort or remove partial download | `CANCELLING` |
| `RECONCILING` | Server upload is `ACTIVE` and URI is usable | Mark only missing parts as pending | `RUNNING` |
| `RECONCILING` | Server upload is `COMPLETING` | Poll later with bounded backoff | `RECONCILING` |
| `RECONCILING` | Server upload is `COMPLETED` | Persist returned file metadata | `COMPLETED` |
| `RECONCILING` | Server upload is `ABORTED` or `EXPIRED` | Store non-retryable terminal cause | `FAILED` |
| `RECONCILING` | URI permission is unavailable | Stop automatic retry and request user selection | `REQUIRES_RESELECTION` |
| `FAILED` | User/WorkManager retries and `retryable=true` | Recheck authoritative state | `RECONCILING` |
| `FAILED` | User cancels an upload whose server session may remain | Request abort | `CANCELLING` |
| `REQUIRES_RESELECTION` | User grants a usable replacement URI | Recheck authoritative state | `RECONCILING` |
| `REQUIRES_RESELECTION` | User cancels | Request abort or local cleanup | `CANCELLING` |
| `CANCELLING` | Backend confirms `ABORTED`/`EXPIRED`, or download partial cleanup succeeds | Persist cleanup result | `CANCELLED` |
| `CANCELLING` | Backend reports completion won the race | Persist final file metadata | `COMPLETED` |
| `CANCELLING` | Transient abort/cleanup failure | Retry with bounded backoff | `CANCELLING` |

After process recreation, any locally persisted `RUNNING` or `CANCELLING` transfer resumes in `RECONCILING` or cancellation recovery before new file bytes are sent.

### Pause and cancellation semantics

- Upload pause is local only. It retains the server session and quota reservation; the 24-hour server inactivity lifetime still applies.
- Download pause retains the durable partial file and object version.
- Upload cancellation is not `CANCELLED` until the backend confirms abort/expiry. A completion that wins the race produces `COMPLETED`.
- Download cancellation deletes the partial file. If deletion is temporarily unavailable, the transfer remains `CANCELLING` and cleanup retries.
- `FAILED` stores `retryable` and a stable error code. Only retryable failures may return to `RECONCILING` without creating a new transfer.

### Download version reconciliation

If the remote object version matches the stored version, download resumes from the durable local byte count. If it differs, Android discards or replaces the partial file, informs the user, and restarts from byte zero; bytes from different versions are never appended together.

### Multi-device boundary

Cross-device continuation of an active upload is not intentionally supported in this release. Each device prevents duplicate local workers for the same transfer. Server idempotency preserves correctness if duplicate requests occur, but the product does not provide a distributed execution lease or a transfer-handoff workflow.

