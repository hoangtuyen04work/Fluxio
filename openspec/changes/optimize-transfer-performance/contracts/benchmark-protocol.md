# Transfer Benchmark Protocol

## Scope and evidence boundary

This protocol selects and accepts transfer defaults for the Fluxio demonstration environment. Measurements run only on a fixed Android Studio Emulator because no physical Android device is available. Results MUST NOT be presented as physical-device, battery, radio, thermal, or production certification.

A future production-readiness change MUST repeat performance and energy validation on representative physical Android devices. Battery consumption is deliberately outside the acceptance criteria for this change.

## Fixed test environment

Every report MUST record the host CPU, host RAM, host operating system, Android Studio version, emulator device profile, Android API level, emulator CPU cores, emulator RAM, JVM/application heap limit, backend build, MinIO build, and commit identifier. Hardware acceleration and all other host workloads that could materially affect results MUST remain fixed across a comparison.

Emulator snapshots MAY be used for setup, but every measured run starts from the same application and storage state. Emulator results are compared only with results from the same recorded host and emulator configuration.

## Test files

| Size | Purpose |
|---:|---|
| 10 MiB | Control-plane and startup overhead |
| 100 MiB | Typical transfer behavior |
| 1 GiB | Sustained throughput and candidate selection |
| 5 GiB | Maximum-size stability and bounded-memory validation |

Test content MUST be deterministic and its size and checksum recorded. Upload validation compares the completed object size and checksum; download validation compares the durable local result with the source checksum.

## Network profiles

| Profile | Bandwidth | Latency | Packet loss | Purpose |
|---|---:|---:|---:|---|
| Good Wi-Fi | 100 Mbps down / 100 Mbps up | 20 ms | 0.1% | Throughput, CPU, memory, and stable completion gates |
| Interrupted | Good Wi-Fi baseline with two forced 30-second outages | 20 ms outside outages | 0.1% outside outages | Resume, retry cost, cancellation, and reconciliation gates |

Throughput acceptance applies only to Good Wi-Fi. The Interrupted profile has correctness, recovery-time, completion, and redundant-byte gates but no minimum throughput gate.

## Candidate matrix

| Parameter | Candidates |
|---|---|
| Multipart part size | 5 MiB, 8 MiB, 16 MiB |
| Maximum in-flight parts | 1, 2, 4 |

The initial reference candidate is 8 MiB with concurrency 2. It is not the selected default until it passes this protocol.

## Execution plan

### Candidate-selection round

- Use the fixed emulator and a 1 GiB file.
- Test all nine part-size/concurrency combinations on both network profiles.
- Run one unreported warm-up, then five measured runs per combination and profile.
- Reset application transfer state, MinIO test object, and quota accounting between runs.
- Randomize candidate order within each profile to reduce host-temperature and background-load bias.

This produces 90 measured candidate runs.

### Acceptance round

- Test only the selected candidate.
- Run each of the four file sizes five times on Good Wi-Fi.
- Run each of the four file sizes ten times on the Interrupted profile.
- Exercise upload, resume, complete, ranged download, pause, cancellation, expiry cleanup, and completion reconciliation where applicable.

## Metrics and numeric gates

| Area | Metric | Acceptance gate |
|---|---|---|
| Correctness | Object/local size and checksum | 100% match; zero corruption |
| Stable completion | Planned Good Wi-Fi runs | At least 99% completion and no crash or OOM; with the defined sample all planned runs are expected to pass |
| Interrupted completion | Forced-outage runs | 10/10 per tested file size eventually complete within the retry policy |
| Good Wi-Fi throughput | Median application payload throughput for files at least 100 MiB | At least 75% of configured directional bandwidth |
| Memory | Peak transfer heap increase | No more than `min(96 MiB, 35% of app heap limit)` |
| Memory scaling | Peak heap from 1 GiB to 5 GiB | Increase no greater than 10%; no whole-file temporary copy |
| CPU | Average app CPU while steadily transferring | No more than 50% of one core-equivalent in the recorded emulator environment |
| Idle CPU | Paused, offline-backoff, or retry-wait state | No busy loop; CPU returns near recorded idle baseline |
| Resume start | Connectivity restored to reconciliation start | No more than 5 seconds |
| Resume data | Connectivity stable to payload transfer resuming | No more than 15 seconds |
| Retry duplication | Bytes resent after interruption | No confirmed part resent; redundant bytes no more than `partSize × concurrency` |
| Retry limit | Individual part attempts | At most five attempts before `FAILED`; capped jittered backoff no greater than 30 seconds |
| Quota/idempotency | Counters and final file records | Zero duplicate file records and zero quota inconsistency |
| Control-plane capacity | One Spring Boot instance | 100 concurrent transfers and 20 control requests per second |
| Control-plane latency | Create, sign, list, abort | p95 below 500 ms under the stated capacity |
| Completion latency | Complete operation with healthy MinIO | p95 below 3 seconds under the stated capacity |
| Control-plane reliability | Server-attributable errors | Below 1% |

CPU measurements on an emulator are valid only for regression comparisons on the identical recorded host. They are not a claim about physical-device CPU, thermal behavior, or battery consumption.

## Candidate selection rule

1. Reject any candidate that fails correctness, completion, memory, retry, quota, or idempotency gates.
2. Rank remaining candidates by median Good Wi-Fi throughput.
3. If another candidate is within 10% of the fastest throughput but has materially lower memory or CPU cost, select the lighter candidate.
4. Prefer one fixed default. Adaptive concurrency is introduced only if the evidence shows no fixed candidate satisfies the emulator matrix.
5. Record the selected values, raw measurements, and rationale in this document and `design.md`; do not silently change defaults.

## Regression gates

Compare only with the last accepted baseline produced on the same host/emulator configuration.

- Median throughput regression greater than 15% fails.
- p95 completion-time regression greater than 20% fails.
- Peak heap regression greater than 10%, or any memory-limit violation, fails.
- CPU-cost regression greater than 15% without a corresponding throughput benefit fails.
- Any completion-rate gate failure fails.
- Any corruption, OOM, duplicate file, or quota inconsistency fails immediately.

## Evidence format

The benchmark output MUST preserve machine-readable CSV or JSON raw results plus a Markdown summary. Each measured run records build/commit, environment fingerprint, direction, file size, network profile, part size, concurrency, total duration, payload throughput, completion result, peak heap, average CPU, retry count, redundant bytes, reconciliation timing, checksum result, and error code.

The summary reports count, median, p95, completion rate, pass/fail per gate, selected candidate, and known limitations. Battery and physical-device conclusions MUST be explicitly marked as not measured.

