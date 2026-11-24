# Fluxio Member 1 Streaming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide bounded-memory Android range I/O and the mandatory `B0`/`B1` baselines used by upload, download, and benchmark work.

**Architecture:** A small `transfer/io` package owns URI metadata, seekable range reads, fixed-buffer streaming, and checksums. `B0` intentionally stages the full ordinary file in cache; `B1` sends the `RangeSource` directly through an OkHttp request body.

**Tech Stack:** Kotlin 2.2, Android SDK 36/minSdk 26, ContentResolver, ParcelFileDescriptor/FileChannel, OkHttp/Okio, Coroutines, JUnit, Android instrumentation tests, MockWebServer.

**Spec:** [`openspec/changes/optimize-transfer-performance/team-work-packages.md`](../../../openspec/changes/optimize-transfer-performance/team-work-packages.md), sections 3 and 7.

## Global Constraints

- Optimized streaming must not create a whole-file temporary copy.
- Range bounds use `Long`; reject negative offset/length and offset+length overflow.
- Copy exactly `length` bytes or fail without reporting optimistic progress.
- `B0` is unencrypted whole-file staging and is mandatory only for 10 MiB and 100 MiB.
- `B1` is direct fixed-buffer single PUT and must support all four benchmark sizes.
- Persisted URI permission is trusted only after Android confirms it; unavailable permission maps to `REQUIRES_RESELECTION` in Member 4.

## Review Focus

- Zero-byte and final short reads must not spin forever.
- A provider that cannot seek must fail with a stable `SourceNotSeekable` error, not silently read the wrong offset.
- Range arithmetic near `Long.MAX_VALUE` must reject overflow.
- Cancellation must close descriptors/streams promptly.
- A provider reporting unknown size must be rejected for multipart planning but may still be read in `B0` only when a measured size is obtained during staging.

---

### Task 1: Add streaming test dependencies and package skeleton

**Files:**
- Modify: `Fluxio-Android/gradle/libs.versions.toml`
- Modify: `Fluxio-Android/app/build.gradle.kts`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/io/RangeSource.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/io/TransferIoException.kt`
- Test: `Fluxio-Android/app/src/test/java/com/example/fluxio/transfer/io/RangeValidationTest.kt`

**Interfaces:**
- Produces: `RangeSource`, `TransferIoException.InvalidRange`, `TransferIoException.SourceNotSeekable`, `TransferIoException.UnexpectedEnd`.

- [ ] **Step 1: Add explicit Okio and MockWebServer aliases plus test dependencies**

Add `okio`, `mockwebserver`, and `kotlinx-coroutines-test` to the version catalog and app test configuration; do not change production versions already locked unless dependency resolution proves incompatibility.

- [ ] **Step 2: Write failing `RangeValidationTest`**

Test `validateRange(sizeBytes, offset, length)` for negative values, overflow, end past size, zero length, and the exact final byte.

- [ ] **Step 3: Run the focused test and confirm failure**

Run: `cd Fluxio-Android; .\gradlew.bat testDebugUnitTest --tests "com.example.fluxio.transfer.io.RangeValidationTest"`

Expected: FAIL because `RangeSource`/`validateRange` does not exist.

- [ ] **Step 4: Implement the minimal interface and validation**

```kotlin
interface RangeSource {
    val sizeBytes: Long
    suspend fun copyRange(offset: Long, length: Long, sink: BufferedSink): Long
}

fun validateRange(sizeBytes: Long, offset: Long, length: Long)
```

- [ ] **Step 5: Run the focused test and commit**

Expected: PASS.

```powershell
git add Fluxio-Android/gradle/libs.versions.toml Fluxio-Android/app/build.gradle.kts Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/io Fluxio-Android/app/src/test/java/com/example/fluxio/transfer/io
git commit -m "feat(android): define bounded range source"
```

### Task 2: Implement Content URI range streaming

**Files:**
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/io/ContentUriRangeSource.kt`
- Create: `Fluxio-Android/app/src/androidTest/java/com/example/fluxio/transfer/io/ContentUriRangeSourceTest.kt`

**Interfaces:**
- Consumes: `RangeSource`, `validateRange` from Task 1.
- Produces: `ContentUriRangeSource(context: Context, uri: Uri, sizeBytes: Long, bufferBytes: Int = 64 * 1024)`.

- [ ] **Step 1: Write instrumentation tests**

Cover exact middle range, final short range, zero length, cancellation/close, and a non-seekable provider error. Assert sink bytes exactly match the source slice and returned count equals `length`.

- [ ] **Step 2: Run and confirm failure**

Run on emulator: `cd Fluxio-Android; .\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.fluxio.transfer.io.ContentUriRangeSourceTest`

Expected: FAIL because the class is missing.

- [ ] **Step 3: Implement `ContentUriRangeSource`**

Use `ContentResolver.openFileDescriptor(uri, "r")`, position a `FileChannel` to the requested offset, copy through one fixed buffer, call `ensureActive()` between reads, and throw `UnexpectedEnd` if fewer bytes are available.

- [ ] **Step 4: Run instrumentation tests and commit**

```powershell
git add Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/io/ContentUriRangeSource.kt Fluxio-Android/app/src/androidTest/java/com/example/fluxio/transfer/io/ContentUriRangeSourceTest.kt
git commit -m "feat(android): stream bounded content uri ranges"
```

### Task 3: Add streaming SHA-256 support

**Files:**
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/io/RangeChecksum.kt`
- Test: `Fluxio-Android/app/src/test/java/com/example/fluxio/transfer/io/RangeChecksumTest.kt`

**Interfaces:**
- Consumes: `RangeSource`.
- Produces: `suspend fun RangeSource.sha256(offset: Long, length: Long): ByteString`.

- [ ] **Step 1: Write failing checksum tests**

Use an in-memory fake `RangeSource`; assert known SHA-256 for empty, full, and partial ranges and assert the source is read once with bounded chunks.

- [ ] **Step 2: Run the focused test and confirm failure**

Run: `cd Fluxio-Android; .\gradlew.bat testDebugUnitTest --tests "com.example.fluxio.transfer.io.RangeChecksumTest"`

- [ ] **Step 3: Implement streaming digest without collecting the range**

Feed bytes into `MessageDigest` through a hashing sink; return Okio `ByteString`.

- [ ] **Step 4: Run tests and commit**

```powershell
git add Fluxio-Android/app/src/main/java/com/example/fluxio/transfer/io/RangeChecksum.kt Fluxio-Android/app/src/test/java/com/example/fluxio/transfer/io/RangeChecksumTest.kt
git commit -m "feat(android): checksum streamed ranges"
```

### Task 4: Implement mandatory B0 and B1 bodies

**Files:**
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/benchmark/BaselineMode.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/benchmark/StagedPutBody.kt`
- Create: `Fluxio-Android/app/src/main/java/com/example/fluxio/benchmark/StreamingPutBody.kt`
- Test: `Fluxio-Android/app/src/test/java/com/example/fluxio/benchmark/BaselinePutBodyTest.kt`

**Interfaces:**
- Consumes: `RangeSource`.
- Produces: `BaselineMode.B0_STAGED_SINGLE_PUT`, `BaselineMode.B1_STREAMING_SINGLE_PUT`, `suspend fun stageToCache(...)`, and `StreamingPutBody(source, mediaType)`.

- [ ] **Step 1: Write MockWebServer tests**

Assert B0 creates/deletes a full temporary file and sends exact bytes; B1 sends exact bytes without creating a cache file; cancellation closes the body; content length equals source size.

- [ ] **Step 2: Run and confirm failure**

Run: `cd Fluxio-Android; .\gradlew.bat testDebugUnitTest --tests "com.example.fluxio.benchmark.BaselinePutBodyTest"`

- [ ] **Step 3: Implement B0/B1 minimal request bodies**

Keep encryption disabled. B0 records staged bytes and staging duration. B1 reads `RangeSource` directly with fixed buffering.

- [ ] **Step 4: Run tests, then all Android unit tests**

Run: `cd Fluxio-Android; .\gradlew.bat testDebugUnitTest`

- [ ] **Step 5: Commit**

```powershell
git add Fluxio-Android/app/src/main/java/com/example/fluxio/benchmark Fluxio-Android/app/src/test/java/com/example/fluxio/benchmark
git commit -m "feat(android): add B0 and B1 benchmark baselines"
```

### Task 5: Publish Member 1 evidence

**Files:**
- Create: `openspec/changes/optimize-transfer-performance/evidence/member-1-streaming.md`
- Modify: `openspec/changes/optimize-transfer-performance/contracts/traceability-matrix.md`

**Interfaces:**
- Produces: evidence links for `HPFT-006`, `HPFT-024`, and `HPFT-025` after tests/measurements exist.

- [ ] **Step 1: Record commands, emulator configuration, test results, and known limits**

- [ ] **Step 2: Link only verified rows; leave unmeasured benchmark rows `SPECIFIED`**

- [ ] **Step 3: Run OpenSpec validation**

Run: `openspec validate --all`

- [ ] **Step 4: Commit**

```powershell
git add openspec/changes/optimize-transfer-performance/evidence/member-1-streaming.md openspec/changes/optimize-transfer-performance/contracts/traceability-matrix.md
git commit -m "docs: record streaming implementation evidence"
```
