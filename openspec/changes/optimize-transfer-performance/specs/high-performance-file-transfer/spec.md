# Spec Delta

## Purpose

Define observable performance, resource, reliability, and resumability behavior for Android file transfers without requiring application-layer file encryption.

## ADDED Requirements

### Requirement: File size and account quota

The first performance release SHALL accept files up to and including 5 GiB. Each user SHALL have a 20 GiB quota calculated from active completed files plus storage reserved by active upload sessions. A request that would exceed either limit SHALL be rejected before multipart data transfer begins.

#### Scenario: File is within the size limit and quota

- **WHEN** an authenticated user initiates an upload no larger than 5 GiB and the reserved size keeps usage at or below 20 GiB
- **THEN** the service creates the upload session and reserves the declared plaintext size against the user's quota

#### Scenario: File exceeds 5 GiB

- **WHEN** the declared file size is greater than 5 GiB
- **THEN** the service rejects upload initialization without creating a multipart upload or quota reservation

#### Scenario: Upload would exceed account quota

- **WHEN** active stored usage plus active reservations plus the requested file size would exceed 20 GiB
- **THEN** the service rejects upload initialization and reports the quota limit and current usage

#### Scenario: Upload reservation is released

- **WHEN** an upload is completed, aborted, cancelled, or expired
- **THEN** its temporary reservation is converted to completed-file usage or released exactly once

#### Scenario: Upload is being initialized

- **WHEN** quota has been reserved and MinIO multipart initialization has not reached a proven terminal outcome
- **THEN** the `INITIALIZING` session continues to contribute to reserved usage until it becomes `ACTIVE` or is aborted and released exactly once

### Requirement: Streaming multipart upload

The client SHALL upload large files as independently retryable multipart parts without creating a complete temporary copy of the source file. Memory use SHALL be bounded by configured part size and in-flight concurrency rather than total file size.

#### Scenario: Upload a file larger than available memory

- **WHEN** the user uploads a file larger than the application's available heap
- **THEN** the client streams bounded parts and completes the upload without buffering the whole file in memory

### Requirement: Bounded parallelism

The client SHALL limit concurrent part transfers to a configured bound and SHALL reduce or retry work when network or server errors indicate overload.

#### Scenario: Multiple parts are ready

- **WHEN** several upload parts are pending
- **THEN** no more than the configured maximum number are transferred concurrently

### Requirement: Resumable upload

The system SHALL persist enough transfer state to reconcile uploaded parts after process death, network loss, or application restart and SHALL avoid retransmitting parts already confirmed by object storage.

#### Scenario: Application restarts during upload

- **WHEN** the user resumes an incomplete transfer after application restart
- **THEN** the client reconciles local and server part state and uploads only missing parts

#### Scenario: Local source permission is unavailable

- **WHEN** the application restarts and no longer has permission to reopen the source URI
- **THEN** the transfer becomes `REQUIRES_RESELECTION` and does not retry file reads until the user grants a usable URI

### Requirement: Deterministic transfer lifecycle

The server and Android client SHALL enforce the state transitions, allowed commands, terminal behavior, pause semantics, cancellation recovery, and time policy defined in `contracts/state-machines.md`. Concurrent or repeated commands SHALL NOT apply quota, completion, or cancellation effects more than once.

#### Scenario: Active upload expires

- **WHEN** an `ACTIVE` upload has no successful activity for 24 hours
- **THEN** the backend aborts its multipart state, releases its reservation exactly once, and reports the session as expired

#### Scenario: Completion outcome is temporarily unknown

- **WHEN** a session remains `COMPLETING` for more than two minutes
- **THEN** the backend reconciles object storage and database state instead of expiring or speculatively aborting the session

#### Scenario: Abort is requested during completion

- **WHEN** the owner requests abort while the session is `COMPLETING`
- **THEN** the service rejects the abort with `409 UPLOAD_COMPLETION_IN_PROGRESS` and reconciliation determines whether the terminal state is `COMPLETED` or `ABORTED`

#### Scenario: User pauses an upload

- **WHEN** the user pauses an Android upload
- **THEN** the client stops scheduling new part requests, retains local and server state, and reconciles before resuming without aborting the server session

#### Scenario: Application restarts during cancellation

- **WHEN** Android is recreated while a transfer is `CANCELLING`
- **THEN** it recovers cancellation and remains non-terminal until backend abort/expiry or local download cleanup is confirmed

### Requirement: Reliable completion and cleanup

The backend SHALL complete a multipart upload only with the ordered set of confirmed parts. Cancelled, expired, or abandoned sessions SHALL be aborted and cleaned up.

#### Scenario: Upload session expires

- **WHEN** an incomplete session exceeds its configured lifetime
- **THEN** the backend aborts the multipart upload and marks the transfer unavailable for further parts

### Requirement: Resumable download

The client SHALL support ranged continuation of an interrupted download when the stored object version has not changed.

#### Scenario: Download connection is interrupted

- **WHEN** a partial local download exists and the remote object version still matches
- **THEN** the client resumes from the confirmed byte offset instead of restarting from byte zero

#### Scenario: Downloaded object version changed

- **WHEN** a partial download exists and the remote object version differs from the stored version
- **THEN** the client discards or replaces the partial data, informs the user, and restarts from byte zero without combining versions

#### Scenario: User cancels a download

- **WHEN** the user cancels a partial download
- **THEN** the client deletes the partial file or remains `CANCELLING` until deferred cleanup succeeds

### Requirement: Observable progress and cancellation

The client SHALL expose transferred bytes, total bytes, current state, retry state, and cancellation for active transfers.

#### Scenario: User cancels an active upload

- **WHEN** the user cancels a transfer
- **THEN** active requests stop, local state becomes `CANCELLING`, the backend abort operation is requested, and the transfer becomes `CANCELLED` only after abort or expiry is confirmed

### Requirement: Benchmark-gated defaults

Default part size and concurrency for the demonstration build SHALL be selected from documented measurements on a fixed Android Studio Emulator across representative file sizes and the Good Wi-Fi and Interrupted profiles defined in `contracts/benchmark-protocol.md`, rather than assumed values. The evidence SHALL NOT be represented as physical-device, battery, thermal, radio, or production certification.

#### Scenario: Select demonstration defaults

- **WHEN** part size or concurrency defaults are proposed for release
- **THEN** benchmark evidence reports Good Wi-Fi throughput, peak memory, emulator CPU, retry cost, reconciliation time, correctness, and completion rate for the selected values

#### Scenario: Throughput is accepted

- **WHEN** the selected configuration transfers a file of at least 100 MiB on the Good Wi-Fi profile
- **THEN** its median application payload throughput is at least 75% of configured directional bandwidth

#### Scenario: Network is interrupted

- **WHEN** connectivity is forcibly removed twice for 30 seconds during a transfer
- **THEN** all ten acceptance runs per tested file size resume without retransmitting confirmed parts and eventually complete within the retry policy

#### Scenario: Emulator evidence is reported

- **WHEN** benchmark results are published for this change
- **THEN** they identify the fixed host and emulator configuration and explicitly state that physical-device and battery behavior were not measured
