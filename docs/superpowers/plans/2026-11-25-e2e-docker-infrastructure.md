# E2E Docker Infrastructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a repeatable PostgreSQL + MinIO Docker environment that supports a host-run Spring Boot backend and Android Emulator E2E testing.

**Architecture:** Docker Compose owns PostgreSQL, MinIO, durable E2E volumes, healthchecks, and idempotent bucket initialization. Spring Boot runs on Windows with an `e2e` profile, uses `127.0.0.1` for JDBC/S3 control operations, and signs Android data-plane URLs against `10.0.2.2`.

**Tech Stack:** Docker Compose, PostgreSQL 17 Alpine, MinIO/S3, Spring Boot 4, AWS SDK v2, Flyway, PowerShell.

**Spec:** `docs/superpowers/specs/2026-11-25-e2e-docker-environment-design.md`

## Global Constraints

- Docker contains PostgreSQL and MinIO only; Spring Boot remains a host process.
- Docker resources use a dedicated Compose project and named volumes.
- Local defaults are deterministic but every credential/port can be overridden by environment variables.
- S3 client and presigner endpoints are separate because the signed Host header cannot be rewritten.
- Existing single-endpoint configurations remain compatible.
- Cleanup must preserve volumes unless the user passes an explicit destructive switch.

## Review Focus

- Presigned URLs must contain `10.0.2.2:9000`, while backend S3 calls must use `127.0.0.1:9000`.
- Bucket initialization must be idempotent after repeated Compose starts.
- Spring Boot must not start before PostgreSQL and MinIO are usable.
- Compose ports must not collide silently; overrides must be documented.
- Cleanup must never remove unrelated Docker resources or volumes.

---

### Task 1: Separate internal S3 and public presigner endpoints

**Files:**
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/storage/StorageProperties.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/storage/S3MultipartObjectStore.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/transfer/TransferConfiguration.java`
- Modify: `fluxio/src/main/java/com/example/fluxio/FileTransferController.java`
- Test: `fluxio/src/test/java/com/example/fluxio/transfer/storage/S3MultipartObjectStoreIT.java`

**Interfaces:**
- Consumes: `fluxio.storage.endpoint` and optional `fluxio.storage.public-endpoint`.
- Produces: S3 control operations through the internal endpoint and presigned URLs through the public endpoint.

- [ ] **Step 1: Add failing endpoint-separation tests**

Assert that a store configured with internal MinIO plus public `http://10.0.2.2:9000` returns upload/download presigned URLs whose host is `10.0.2.2`, while create/list/abort still succeed against the internal container endpoint.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `cd fluxio; mvn '-Dtest=S3MultipartObjectStoreIT' test`

Expected: compilation or assertion failure because only one endpoint exists.

- [ ] **Step 3: Implement endpoint separation**

Add `publicEndpoint` to `StorageProperties`; build `S3Client` from `endpoint` and `S3Presigner` from `publicEndpoint`. Bind `${fluxio.storage.public-endpoint:${fluxio.storage.endpoint}}` in Spring. Give the legacy controller a separate signing client while keeping the control client internal.

- [ ] **Step 4: Run focused and backend test suites**

Run: `cd fluxio; mvn '-Dtest=S3MultipartObjectStoreIT' test; mvn test`

Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add fluxio/src/main fluxio/src/test/java/com/example/fluxio/transfer/storage/S3MultipartObjectStoreIT.java
git commit -m "feat(server): separate storage and presign endpoints"
```

### Task 2: Add Docker Compose infrastructure and Spring E2E profile

**Files:**
- Create: `docker-compose.e2e.yml`
- Create: `.env.e2e.example`
- Create: `fluxio/src/main/resources/application-e2e.yaml`
- Create: `scripts/e2e/start-e2e.ps1`
- Create: `scripts/e2e/verify-e2e.ps1`
- Create: `scripts/e2e/stop-e2e.ps1`

**Interfaces:**
- Consumes: Docker Desktop and the E2E environment variables documented in `.env.e2e.example`.
- Produces: healthy PostgreSQL on `127.0.0.1:5432`, healthy MinIO on `127.0.0.1:9000`, console on `127.0.0.1:9001`, and private bucket `fluxio`.

- [ ] **Step 1: Write Compose and profile configuration**

Use PostgreSQL 17 Alpine, the MinIO image validated by repository integration tests, healthchecks, named volumes, and a one-shot idempotent bucket initializer. Configure JDBC/Flyway, host binding, internal storage endpoint, and public emulator endpoint in `application-e2e.yaml`.

- [ ] **Step 2: Add safe lifecycle scripts**

`start-e2e.ps1` renders Compose, starts services, waits for health and confirms bucket initialization. `verify-e2e.ps1` performs read-only checks. `stop-e2e.ps1` preserves volumes by default and only removes them with `-RemoveVolumes`.

- [ ] **Step 3: Validate and build infrastructure**

Run: `docker compose --env-file .env.e2e.example -f docker-compose.e2e.yml config`

Run: `docker compose --env-file .env.e2e.example -f docker-compose.e2e.yml build`

Expected: both commands exit 0.

- [ ] **Step 4: Start and verify health**

Run: `.\scripts\e2e\start-e2e.ps1`

Run: `.\scripts\e2e\verify-e2e.ps1`

Expected: PostgreSQL and MinIO healthy; bucket initializer exits 0; bucket `fluxio` exists and is private.

- [ ] **Step 5: Commit**

```powershell
git add docker-compose.e2e.yml .env.e2e.example fluxio/src/main/resources/application-e2e.yaml scripts/e2e
git commit -m "feat(infra): add postgres and minio e2e environment"
```

### Task 3: Verify host backend startup and document handoff

**Files:**
- Modify: `README.md`
- Modify: `docs/testing/TEST-SCENARIOS-AND-RESULTS.md`
- Create: `docs/testing/results/2026-11-25-e2e-infrastructure.md`

**Interfaces:**
- Consumes: healthy Task 2 infrastructure and the `e2e` Spring profile.
- Produces: exact commands for the user to run backend and Android against the prepared environment.

- [ ] **Step 1: Start backend with E2E profile**

Run: `cd fluxio; mvn spring-boot:run '-Dspring-boot.run.profiles=e2e'`

Expected: Flyway reaches v2, Tomcat listens on port 8080, and storage initialization succeeds.

- [ ] **Step 2: Run smoke checks**

Verify login on `http://127.0.0.1:8080`, create a multipart upload, request part URLs and a download ticket, and confirm all data-plane URLs use `10.0.2.2:9000`.

- [ ] **Step 3: Run regression verification**

Run: `cd fluxio; mvn test; mvn '-Dtest=*IT' test`

Run: `cd Fluxio-Android; .\gradlew.bat testDebugUnitTest assembleDebug`

Run: `openspec validate --all`

Expected: every command exits 0.

- [ ] **Step 4: Document startup and Android settings**

Document Compose start/verify/stop commands, backend launch, Android endpoint `http://10.0.2.2:8080/`, MinIO console, local credentials, evidence locations, and the boundary that new transfer-engine UI wiring is separate.

- [ ] **Step 5: Commit**

```powershell
git add README.md docs/testing
git commit -m "docs: add e2e infrastructure runbook"
```
