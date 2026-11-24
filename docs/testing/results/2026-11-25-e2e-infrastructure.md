# E2E infrastructure verification — 2026-11-25

## Images

| Image | Image ID |
|---|---|
| `fluxio-e2e-postgres:17` | `sha256:973fd038ea20242a19c5bd857f879ed3360fa58c276380168cf8b566d718bbe6` |
| `fluxio-e2e-minio:2025.5.24` | `sha256:4f9f5a8c6029b6f52bec663e7aba3662600bd179f10752b50895c921d2efcd31` |

## Runtime verification

- PostgreSQL 17 container: `healthy`, published at `127.0.0.1:5432`.
- MinIO 2025.5.24 container: `healthy`, API/console at `127.0.0.1:9000/9001`.
- `minio-init`: exit code `0`; private bucket `fluxio` exists.
- Spring profile: `e2e`.
- Flyway: two migrations applied; schema version `v2`.
- Login: HTTP 200 using the local E2E account.
- Multipart create: state `ACTIVE`.
- Presigned upload-part URL: host `10.0.2.2`, port `9000`.
- Smoke upload session cleanup: HTTP 204.

## User handoff

Keep Docker containers running. Start backend in a separate terminal:

```powershell
Set-Location D:\KMA\Fluxio\fluxio
$env:SPRING_PROFILES_ACTIVE = "e2e"
mvn spring-boot:run
```

Then run Android debug on an emulator and enter `http://10.0.2.2:8080/` as the server URL.
