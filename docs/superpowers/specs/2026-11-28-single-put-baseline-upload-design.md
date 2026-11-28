# Single-PUT Baseline Upload Design

## Intent

Add an ordinary upload path beside Fluxio's optimized multipart path so the Android app can demonstrate and measure the effect of multipart streaming, bounded concurrency, persistence, and resume. The ordinary path is the `B1` baseline: Android streams the selected content URI directly to MinIO with one fixed-buffer PUT. It does not create a whole-file cache copy, split the file, upload parts concurrently, or resume an interrupted request.

## Goals

- Let the user choose **Normal upload (single PUT)** or **Optimized upload (multipart + resume)** before selecting a file.
- Give both paths the same authentication, file-size limit, quota reservation, PostgreSQL metadata, private MinIO bucket, and benchmark instrumentation.
- Isolate transfer architecture in comparisons: both paths upload the same unencrypted source bytes and use equivalent HTTP/storage configuration.
- Preserve the existing optimized multipart API and state machine.
- Record enough evidence to compare duration, throughput, startup time, heap, temporary storage, retries, redundant bytes, completion, and interruption behavior.

## Non-goals

- Adding resume, part retries, or parallelism to the normal path.
- Treating the old FXM1 encryption-and-cache flow as the normal path.
- Replacing the mandatory `B0` through `B4` benchmark matrix. B1 becomes user-accessible; B0 remains a benchmark-only historical control.
- Claiming production performance from emulator measurements.
- Changing the download design.

## Constraints and fairness rules

- Maximum file size remains 5 GiB and account quota remains 20 GiB.
- File bodies travel directly between Android and MinIO; Spring Boot remains control plane only.
- Neither comparison path performs application-layer encryption during benchmark runs.
- A comparison uses the same source file, account, backend build, MinIO instance, emulator fingerprint, and network profile.
- A normal upload interrupted after sending bytes restarts its single PUT from byte zero. Those resent bytes and the failed attempt are measured, not hidden.
- Presigned URLs are short-lived and never persisted or logged.
- Authentication tokens remain memory-only in this change. After process death or token expiry, background work stops in an authentication-required state and waits for the user to sign in again; adding refresh tokens or durable credentials is out of scope.
- A source URI must expose a trustworthy exact byte size. Unknown-size sources are rejected with a reselection/error message rather than pre-scanned, because pre-scanning would distort B1 startup and I/O measurements.

## Approaches considered

### Selected: dedicated baseline API with shared metadata and quota

Create a small single-upload API separate from the multipart endpoints. It reserves quota and writes the same `stored_file` metadata as the optimized path. This keeps the baseline behavior explicit while making the resulting measurements and stored files comparable.

### Rejected: retain `/api/v1/mvp/files`

The existing MVP endpoint is quick to reuse but bypasses the target metadata, quota, idempotency, and recovery contracts. Results would not be operationally comparable to optimized uploads.

### Rejected: add `mode=SINGLE|MULTIPART` to `/api/v1/uploads`

A unified create endpoint reduces endpoint count but makes multipart DTOs and lifecycle states conditional. It risks complicating a working optimized control plane solely for a research baseline.

## Backend API

### Create a single upload

`POST /api/v1/single-uploads`

Requires JWT and `Idempotency-Key`. The JSON request contains `fileName`, `contentType`, and `sizeBytes`. The backend:

1. validates the 1-byte to 5-GiB size range;
2. atomically reserves quota;
3. creates a single-upload session with a deterministic owner-scoped object key;
4. returns `201` with `uploadId`, `fileId`, one presigned PUT URL, `expiresAt`, and required request headers.

The response never exposes storage credentials. Repeating the same key and request returns the same logical session and may return a freshly signed URL; reusing the key with different content returns `409`.

### Refresh the PUT URL

`POST /api/v1/single-uploads/{uploadId}/url`

Returns a fresh URL only while the session is active and owner-scoped. Refreshing a URL does not imply that a partially transmitted PUT can resume.

### Complete a single upload

`POST /api/v1/single-uploads/{uploadId}/complete`

Requires `Idempotency-Key`. The backend claims completion, performs `HEAD` on the deterministic object, verifies the exact declared size, records ETag/object version, converts reserved bytes to active bytes exactly once, creates the normal `stored_file` row, and returns the existing `FileMetadata` response. Repeated completion returns the same file.

If the object is missing or its size differs, completion fails without activating metadata. A reconciliation job can finish the database transaction after an object was uploaded but the process stopped during completion.

### Abort a single upload

`DELETE /api/v1/single-uploads/{uploadId}`

Marks the session aborted, deletes the object if it exists, and releases reserved quota exactly once. Expiry cleanup performs the same bounded recovery for abandoned sessions. Repeated abort returns `204`.

All owner mismatches return `404`. Errors use the existing `application/problem+json` format and stable codes.

## Persistence and lifecycle

Reuse `upload_session` and `stored_file`, adding an upload strategy discriminator such as `SINGLE_PUT` or `MULTIPART`. `storage_upload_id`, `part_size_bytes`, and `part_count` are absent for `SINGLE_PUT`; constraints must enforce which fields are valid for each strategy.

Single-PUT sessions use `ACTIVE`, `COMPLETING`, `COMPLETED`, `ABORTED`, and `EXPIRED`. They do not need multipart initialization. The reservation and terminal accounting invariants remain identical to multipart uploads. Cleanup must distinguish an uploaded complete object from an absent or partial PUT; S3-compatible PUT visibility is determined with `HEAD`.

The old `/api/v1/mvp/files` endpoints are not used by the new UI and can be removed only after tests and migration concerns are resolved.

When cleanup or reconciliation finds an expired `ACTIVE` single-upload session, it first checks the deterministic object. An object with the exact declared size is promoted through normal completion so uploaded user data and reserved quota converge; a missing or mismatched object is deleted if present and the reservation is released. Because a previously issued PUT URL may still be in flight, cleanup records the terminal decision and performs a later bounded deletion check for aborted/expired sessions so a late object cannot become orphaned.

## Android flow

The upload screen presents two clearly labeled choices before file selection:

- **Normal upload — single PUT, restarts if interrupted**
- **Optimized upload — multipart, can resume**

The chosen strategy is stored with the local transfer so process recreation does not change behavior. Both paths run through the same WorkManager scheduling, progress UI, cancellation controls, Room transfer record, and measurement recorder.

If WorkManager starts without a valid in-memory JWT, or a control-plane request returns an authentication failure, it records `AUTH_REQUIRED` and stops retrying. After the user signs in, the UI offers to restart the pending transfer. A normal B1 upload restarts its PUT from byte zero; an optimized upload reconciles completed parts and continues with only missing parts.

For normal upload, Android:

1. persists URI access and obtains reliable source metadata;
2. creates the single-upload session;
3. streams the content URI through a fixed-size request buffer directly into one PUT;
4. reports byte progress without creating a full temporary file;
5. calls complete after a successful PUT;
6. on transient failure, records the failed/resent bytes and retries from byte zero under a bounded policy;
7. on cancellation, cancels the HTTP call and asks the control plane to abort.

B1 uses a persistent whole-upload retry budget of five total PUT attempts. It retries connection failures, timeouts, `408`, `429`, and `5xx` with capped exponential backoff and jitter, honoring `Retry-After`; other `4xx` responses fail immediately except that an expired presigned URL is refreshed before the next attempt. Each new PUT begins at byte zero, and the attempt count and resent-byte accounting survive process recreation. Authentication failure transitions to `AUTH_REQUIRED` without consuming further attempts.

The normal path must not call the current `FluxioClient.upload()` implementation because that implementation creates an encrypted whole-file cache artifact and therefore behaves like B0 rather than B1.

## Measurement and comparison

Each attempt records the existing benchmark fields, including strategy/mode, file size, startup and total duration, throughput, peak heap, CPU, temporary-storage bytes, retry count, redundant bytes, completion, and error code. Interrupted B1 runs must count all bytes resent after restart. Optimized runs continue to count only retransmitted part payload.

The ordinary UI is useful for demonstration, but formal comparison still runs through the repeatable benchmark harness with warm-ups, fixed file sizes, fixed network profiles, cleanup between runs, and raw CSV/JSON evidence.

## Verification

- Backend contract tests cover ownership, idempotency, size/quota validation, URL refresh, exact-size completion, repeated completion, abort, expiry, and crash-gap reconciliation.
- MinIO integration tests prove a signed single PUT produces an object that completes into normal metadata.
- Android tests prove direct bounded-buffer streaming, no whole-file cache, progress, cancellation, restart-from-zero retry, and strategy persistence.
- E2E tests upload the same checksum-known file through both modes, validate PostgreSQL/MinIO results, then interrupt both modes and compare recovery behavior.
- Benchmark evidence distinguishes B1 from B4 and never mixes encryption or B0 staging cost into the result.

## Builder decisions

The implementer may choose endpoint DTO class names, Room enum storage representation, buffer size within the existing bounded-memory rules, and exact UI widget style. These choices must not change the API behavior, fairness rules, or recorded metrics above.
