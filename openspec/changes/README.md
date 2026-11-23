# OpenSpec changes

Each behavior change is created in its own folder and follows the `spec-driven` workflow:

`proposal.md` → capability delta specs and optional `design.md` → `tasks.md` → apply → archive.

Canonical current behavior is stored under `../specs/`. Target behavior is authorized only by an active OpenSpec change; historical parallel specification folders are not used.

## Active direction

- [`optimize-transfer-performance/`](./optimize-transfer-performance/) is the active architecture change.
- Progress is tracked in [`optimize-transfer-performance/progress.md`](./optimize-transfer-performance/progress.md).
- Four-member research and implementation ownership is documented in [`optimize-transfer-performance/team-work-packages.md`](./optimize-transfer-performance/team-work-packages.md).
- This branch prioritizes upload/download throughput, bounded memory, multipart transfer, resumability, background execution, and measurable benchmarks.
- Client-side file encryption, FXM1, E2EE key management, and cryptographic sharing are deliberately deferred to a later change.
- HTTPS, authentication, authorization, private MinIO buckets, validation, and ownership checks remain baseline safeguards rather than optional performance features.
- The first performance release limits files to 5 GiB and active storage to 20 GiB per user.
- Existing FXM1 objects are test data and will be deleted; no legacy compatibility path is planned for this release.
