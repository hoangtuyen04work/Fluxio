# Fluxio E2E Docker Environment Design

## Intent

Provide a repeatable local environment for the end-to-end scenarios in `docs/testing/TEST-SCENARIOS-AND-RESULTS.md`: Android Emulator talks to a Spring Boot backend running on the Windows host, while PostgreSQL and MinIO run in Docker.

## Goals

- Start PostgreSQL and MinIO with one Docker Compose command.
- Initialize a private `fluxio` bucket automatically and wait for healthy dependencies.
- Run Spring Boot on the host with an explicit `e2e` profile and Flyway migrations.
- Give the backend an internal MinIO endpoint reachable from Windows and give Android presigned URLs reachable through the emulator gateway.
- Keep credentials and ports deterministic for local E2E use, with environment-variable overrides.
- Provide health/smoke commands and document the exact startup/shutdown sequence.

## Non-goals

- Do not Dockerize Spring Boot or Android.
- Do not claim production-grade secret management, TLS, high availability, or persistence guarantees.
- Do not run destructive cleanup against buckets/databases outside the Compose project.
- Do not select benchmark defaults without measured emulator evidence.

## Constraints

- Android Emulator reaches the Windows host at `10.0.2.2`.
- Spring Boot reaches published Docker ports at `127.0.0.1`.
- S3 Signature V4 signs the request host, so a URL signed for `localhost` cannot be rewritten to `10.0.2.2` afterward.
- PostgreSQL schema must be created by the existing Flyway migrations.
- Local cleartext HTTP remains restricted to the Android debug build and `10.0.2.2`.

## Approaches considered

### Selected: Docker infrastructure, host backend

Docker Compose runs PostgreSQL, MinIO, and a one-shot MinIO bucket initializer. Spring Boot runs directly on Windows. The object-store client uses `http://127.0.0.1:9000`; the presigner uses `http://10.0.2.2:9000`.

This is selected because it makes backend debugging straightforward and matches Android Emulator routing without requiring a backend image.

### Rejected: Dockerize the backend

This improves packaging consistency but adds a backend image, container debugging, extra port/routing configuration, and still requires separate internal/public MinIO endpoints.

### Rejected: one LAN endpoint

Using the host LAN address for both callers avoids split endpoint support but is fragile across DHCP, VPN changes, Windows Firewall, and different developer machines.

## Architecture

```text
Android Emulator
  backend: http://10.0.2.2:8080
  data plane: presigned http://10.0.2.2:9000/fluxio/...
                       |
Windows host           |
  Spring Boot :8080 ---+
    JDBC -> 127.0.0.1:5432
    S3 client -> 127.0.0.1:9000
    S3 presigner -> 10.0.2.2:9000
                       |
Docker Compose         |
  PostgreSQL :5432
  MinIO :9000 / Console :9001
  minio-init (create private bucket, then exit)
```

## Configuration design

### Docker Compose

Create `docker-compose.e2e.yml` with:

- `postgres`: PostgreSQL 17 Alpine, database/user/password scoped to local E2E, named volume, healthcheck using `pg_isready`.
- `minio`: the repository-compatible MinIO image, fixed local credentials, named volume, healthcheck on `/minio/health/live`.
- `minio-init`: waits for MinIO health, configures an alias, creates `fluxio` idempotently, and ensures the bucket is private.
- Ports bound to `127.0.0.1` so services are not exposed to the LAN. Android still reaches these published host ports through `10.0.2.2`.

### Spring profile

Create `fluxio/src/main/resources/application-e2e.yaml` with:

- JDBC URL `jdbc:postgresql://127.0.0.1:5432/fluxio`.
- PostgreSQL credentials supplied by `FLUXIO_DB_*` variables with local defaults.
- Flyway enabled.
- Backend bind address `0.0.0.0` so the emulator can reach port 8080.
- Storage client endpoint `http://127.0.0.1:9000`.
- Storage public endpoint `http://10.0.2.2:9000`.
- Local username/password and bucket properties with environment-variable overrides.

### Storage boundary

Extend storage configuration from one endpoint to two:

- `endpoint`: used only by `S3Client` for create/list/complete/abort/head.
- `public-endpoint`: used only by `S3Presigner` for upload-part and download URLs.

`public-endpoint` defaults to `endpoint` so unit tests and existing deployments remain compatible. Apply the same separation to the legacy MVP controller until it is removed, preventing its E2E path from returning emulator-inaccessible URLs.

### Scripts and documentation

Add PowerShell scripts under `scripts/e2e/`:

- `start-e2e.ps1`: validate Docker, start Compose, wait for healthy services, confirm bucket initialization, then print the backend launch command.
- `verify-e2e.ps1`: check PostgreSQL/MinIO containers, MinIO health, backend health/login reachability, and expected published ports without deleting data.
- `stop-e2e.ps1`: stop Compose while preserving volumes; an explicit switch is required to remove E2E volumes.

Update the repository README and test checklist with the exact workflow.

## Verification

- Docker Compose configuration renders successfully.
- PostgreSQL and MinIO become healthy; `minio-init` exits successfully.
- Flyway reaches schema version `v2` when Spring Boot starts with profile `e2e`.
- Login succeeds through `http://127.0.0.1:8080` and through `http://10.0.2.2:8080` from the emulator.
- Multipart part URLs and download tickets contain host `10.0.2.2:9000`.
- Backend can create/list/complete/head objects through `127.0.0.1:9000`.
- Existing backend tests and Android unit tests remain green.
- E2E data is isolated under the Compose project and cleanup cannot target unrelated Docker resources.

## Risks

- Binding Docker ports only to `127.0.0.1` must be verified from the installed Android Emulator; if this host configuration does not forward them through `10.0.2.2`, bind MinIO/backend test ports to all host interfaces only for the E2E profile and document the firewall implication.
- The Android screen still contains the legacy MVP flow. Environment readiness alone does not prove that the new Room/WorkManager multipart engine is wired into the UI; E2E execution must use the new transfer runtime or add that wiring as a separate implementation task.
- MinIO image availability may vary; the Compose file must use an image already validated by repository integration tests or pin an available immutable release.
