# Kịch bản chạy test và ghi nhận kết quả Fluxio

> Tài liệu này là checklist thực thi và mẫu biên bản kết quả. Mỗi lần chạy, sao chép phần **Biên bản kết quả** ở cuối file, điền ngày giờ, commit, môi trường, kết quả và đường dẫn bằng chứng.

## 1. Phạm vi

Tài liệu bao phủ:

- Backend Spring Boot: unit test, API contract, PostgreSQL và MinIO integration test.
- Android: unit test, build APK và instrumentation test trên emulator.
- End-to-end: upload, resume, complete, download, cancel, cleanup và reconciliation.
- Benchmark B0–B4: throughput, CPU, heap, retry, redundant bytes và recovery time.
- OpenSpec: kiểm tra tính hợp lệ của đặc tả và traceability.

Không được coi unit test là bằng chứng hiệu năng. Thông số hiệu năng chỉ hợp lệ khi được đo trên Android Emulator cố định theo `benchmark-protocol.md`.

## 2. Chuẩn bị môi trường

### 2.1 Công cụ bắt buộc

- Java/JDK tương thích với dự án.
- Maven `3.9.x`.
- Android SDK, `adb` và Android Emulator.
- Docker Desktop để Testcontainers khởi tạo PostgreSQL và MinIO.
- PowerShell 7 hoặc Windows PowerShell.
- OpenSpec CLI.

### 2.2 Kiểm tra nhanh

Chạy từ thư mục gốc `D:\KMA\Fluxio`:

```powershell
git status --short
java -version
mvn -version
docker version
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
openspec validate --all
```

Điều kiện bắt đầu:

- Worktree không có thay đổi ngoài phạm vi test.
- Docker hoạt động.
- Có đúng emulator cần đo trong danh sách `adb devices` và trạng thái là `device`.
- Ghi lại commit bằng `git rev-parse HEAD`.

## 3. Backend unit và API test

### BE-01 — Chạy toàn bộ unit test mặc định

**Mục tiêu:** kiểm tra Spring context, upload API, download ticket, lifecycle, recovery và application service.

```powershell
Set-Location D:\KMA\Fluxio\fluxio
mvn test
```

**Đạt khi:** Maven in `BUILD SUCCESS`, `Failures: 0`, `Errors: 0`.

**Xem kết quả tại:**

- `fluxio/target/surefire-reports/*.txt`
- `fluxio/target/surefire-reports/TEST-*.xml`

| Trường | Kết quả |
|---|---|
| Thời gian chạy | |
| Tổng số test | |
| Passed/Failed/Errors/Skipped | |
| Kết luận | PASS / FAIL |
| Bằng chứng | `fluxio/target/surefire-reports/...` |

### BE-02 — PostgreSQL và MinIO integration test

**Mục tiêu:** kiểm tra migration, quota/idempotency, multipart storage và presigned URL trên dịch vụ thật do Testcontainers quản lý.

```powershell
Set-Location D:\KMA\Fluxio\fluxio
mvn '-Dtest=*IT' test
```

**Test chính:**

- `IdempotencyServiceIT`
- `UploadRepositoryIT`
- `S3MultipartObjectStoreIT`

**Đạt khi:** tất cả integration test qua, không có container lỗi và Maven in `BUILD SUCCESS`.

| Trường | Kết quả |
|---|---|
| PostgreSQL image/version | |
| MinIO image/version | |
| Tổng số test | |
| Passed/Failed/Errors | |
| Kết luận | PASS / FAIL |
| Bằng chứng | `fluxio/target/surefire-reports/...IT.txt` |

### BE-03 — Các kịch bản nghiệp vụ cần xác nhận

| ID | Kịch bản | Kết quả mong đợi | Kết quả thực tế |
|---|---|---|---|
| BE-03.1 | Tạo upload hợp lệ | Trả upload session, part size/count và expiry | |
| BE-03.2 | File lớn hơn 5 GiB | Bị từ chối theo contract | |
| BE-03.3 | Vượt quota 20 GiB | Không tạo reservation sai | |
| BE-03.4 | Dùng lại idempotency key cùng request | Trả lại kết quả cũ | |
| BE-03.5 | Dùng lại key với request khác | Trả conflict | |
| BE-03.6 | Sign/list/complete multipart | Chỉ complete các part hợp lệ | |
| BE-03.7 | Abort hoặc upload hết hạn | Multipart và quota được giải phóng | |
| BE-03.8 | Crash-gap reconciliation | File/session/quota hội tụ đúng trạng thái | |
| BE-03.9 | Download ticket sai owner | Không làm lộ file hoặc URL | |
| BE-03.10 | Download ticket đúng owner | URL có hạn sử dụng và hỗ trợ Range | |

## 4. Android unit test và build

### AND-01 — Chạy toàn bộ unit test

```powershell
Set-Location D:\KMA\Fluxio\Fluxio-Android
.\gradlew.bat testDebugUnitTest
```

**Đạt khi:** Gradle in `BUILD SUCCESSFUL` và không có test thất bại.

**Xem báo cáo HTML:**

`Fluxio-Android/app/build/reports/tests/testDebugUnitTest/index.html`

| Nhóm | Nội dung cần kiểm tra | Kết quả |
|---|---|---|
| API contract | Đúng route multipart và download ticket | PASS / FAIL |
| Chunk planner | Long-safe đến 5 GiB | PASS / FAIL |
| Multipart engine | Reconcile, retry, concurrency, duplicate work | PASS / FAIL |
| Persistence | Room entities và trạng thái bền vững | PASS / FAIL |
| Download engine | Resume offset, object version, cancel | PASS / FAIL |
| Streaming range | Header Range, buffer giới hạn, progress | PASS / FAIL |
| Benchmark harness | Matrix, warm-up, cleanup, exporter và gates | PASS / FAIL |

### AND-02 — Build APK debug

```powershell
Set-Location D:\KMA\Fluxio\Fluxio-Android
.\gradlew.bat assembleDebug
```

**Đạt khi:** `BUILD SUCCESSFUL` và APK mới được tạo.

**APK:** `Fluxio-Android/app/build/outputs/apk/debug/app-debug.apk`

| Trường | Kết quả |
|---|---|
| APK size | |
| SHA-256 | |
| Build duration | |
| Kết luận | PASS / FAIL |

Lấy checksum:

```powershell
Get-FileHash .\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

### AND-03 — Instrumentation test trên emulator

```powershell
Set-Location D:\KMA\Fluxio\Fluxio-Android
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
.\gradlew.bat connectedDebugAndroidTest
```

**Đạt khi:** emulator ở trạng thái `device`, Gradle thành công và không có instrumentation failure.

**Xem kết quả tại:** `Fluxio-Android/app/build/reports/androidTests/connected/debug/`.

| Trường | Kết quả |
|---|---|
| Emulator serial | |
| Device profile/API | |
| Test count | |
| Passed/Failed/Skipped | |
| Kết luận | PASS / FAIL |

## 5. End-to-end Android ↔ Backend ↔ MinIO

### E2E-01 — Upload và complete bình thường

1. Khởi động PostgreSQL, MinIO và backend bằng cấu hình test của dự án.
2. Cài APK lên emulator.
3. Upload file 10 MiB có checksum đã biết.
4. Quan sát trạng thái `QUEUED → RECONCILING → RUNNING → COMPLETED`.
5. Đối chiếu kích thước/checksum object trong MinIO và metadata PostgreSQL.

**Đạt khi:** checksum khớp, một file record, quota chính xác, không còn multipart dang dở.

### E2E-02 — Upload bị gián đoạn và resume

1. Upload file tối thiểu 100 MiB.
2. Sau khi có part confirmed, tắt mạng emulator.
3. Khởi động lại ứng dụng hoặc process.
4. Bật mạng và resume.
5. Kiểm tra danh sách part được gửi sau resume.

**Đạt khi:** chỉ gửi các part thiếu; part đã confirmed không bị gửi lại.

### E2E-03 — Download Range và object-version guard

1. Download một phần file rồi ngắt mạng.
2. Giữ nguyên object version và resume.
3. Lặp lại nhưng thay đổi object version trước khi resume.

**Đạt khi:** version khớp tiếp tục từ offset bền vững; version đổi thì xóa dữ liệu dở và tải lại từ byte 0; checksum cuối cùng khớp.

### E2E-04 — Cancellation

1. Bắt đầu upload và download lớn.
2. Hủy từ WorkManager/UI khi đang truyền.
3. Kiểm tra notification, Room, backend session và file cục bộ.

**Đạt khi:** trạng thái cuối `CANCELLED`, worker dừng, không có busy loop, tài nguyên tạm được xử lý đúng.

### E2E-05 — Cleanup và reconciliation

1. Tạo upload session nhưng không complete.
2. Cho session hết hạn hoặc mô phỏng crash giữa object-store completion và DB commit.
3. Chạy cleanup/reconciliation.

**Đạt khi:** multipart hết hạn bị xóa, quota được giải phóng đúng một lần và trạng thái DB/object store hội tụ.

| ID | File size | Network | Duration | Checksum | Retry | Redundant bytes | Kết luận |
|---|---:|---|---:|---|---:|---:|---|
| E2E-01 | | Good Wi-Fi | | | | | PASS / FAIL |
| E2E-02 | | Interrupted | | | | | PASS / FAIL |
| E2E-03 | | Interrupted | | | | | PASS / FAIL |
| E2E-04 | | | | N/A | | | PASS / FAIL |
| E2E-05 | | | | N/A | | | PASS / FAIL |

## 6. Benchmark hiệu năng B0–B4

Chi tiết chuẩn đo và ngưỡng nằm tại:

`openspec/changes/optimize-transfer-performance/contracts/benchmark-protocol.md`

### 6.1 Ghi lại fingerprint trước khi đo

| Thuộc tính | Giá trị |
|---|---|
| Git commit | |
| Host CPU/RAM/OS | |
| Android Studio | |
| Emulator profile/API | |
| Emulator CPU/RAM | |
| App heap limit | |
| Backend build | |
| PostgreSQL/MinIO build | |
| Network profile | Good Wi-Fi / Interrupted |

Không so sánh kết quả giữa hai fingerprint khác nhau.

### 6.2 Ma trận bắt buộc

| Mode | 10 MiB | 100 MiB | 1 GiB | 5 GiB | Good Wi-Fi | Interrupted |
|---|---|---|---|---|---|---|
| B0 — stage full file + one PUT | Bắt buộc | Bắt buộc | Khi đủ storage | Khi đủ storage | Có | Không bắt buộc |
| B1 — direct streaming one PUT | Có | Có | Có | Có | Có | Không bắt buộc |
| B2 — multipart concurrency 1 | Có | Có | Có | Có | Có | Có |
| B3 — multipart concurrency 2/4 | Có | Có | Có | Có | Có | Có |
| B4 — Room + WorkManager + resume | Có | Có | Có | Có | Có | Có |

### 6.3 Chọn part size và concurrency

Với file 1 GiB, chạy mọi tổ hợp:

- Part size: 5, 8, 16 MiB.
- Concurrency: 1, 2, 4.
- Mỗi tổ hợp/profile: 1 warm-up không báo cáo và 5 lượt đo.
- Hai profile: Good Wi-Fi và Interrupted.
- Tổng cộng: 18 warm-up và 90 lượt đo.

Script tạo hai lần mất mạng 30 giây:

```powershell
Set-Location D:\KMA\Fluxio
.\scripts\benchmark\interrupt-network.ps1 -Serial emulator-5554
```

### 6.4 Trường dữ liệu bắt buộc cho mỗi lượt

Mỗi dòng CSV/JSON phải có:

- Commit và environment fingerprint.
- Mode, direction, file size, network profile.
- Part size và concurrency.
- Startup time và total duration.
- Throughput Mbps.
- Completion result.
- Peak heap và average CPU.
- Temporary-storage bytes.
- Retry count và redundant bytes.
- Reconciliation/recovery time.
- Checksum result và error code.

Lưu raw data tại:

`openspec/changes/optimize-transfer-performance/evidence/raw/`

Tạo báo cáo Markdown từ CSV:

```powershell
Set-Location D:\KMA\Fluxio
.\scripts\benchmark\summarize-results.ps1 `
  -InputCsv .\openspec\changes\optimize-transfer-performance\evidence\raw\results.csv `
  -OutputMarkdown .\openspec\changes\optimize-transfer-performance\evidence\benchmark-summary.md
```

### 6.5 Ngưỡng kết luận chính

| Metric | Ngưỡng đạt |
|---|---|
| Checksum | 100% khớp, không corruption |
| Good Wi-Fi completion | Tất cả lượt kế hoạch hoàn thành, không crash/OOM |
| Interrupted completion | 10/10 mỗi kích thước hoàn thành trong retry policy |
| Throughput ≥100 MiB | Median ≥75 Mbps với profile 100 Mbps |
| Peak heap increase | ≤ min(96 MiB, 35% app heap) |
| Heap scaling 1→5 GiB | Tăng không quá 10% |
| Average CPU | ≤50% của một core-equivalent |
| Resume reconciliation | ≤5 giây sau khi có mạng |
| Payload resume | ≤15 giây sau khi mạng ổn định |
| Redundant bytes | ≤ partSize × concurrency |
| Control API p95 | <500 ms |
| Complete p95 | <3 giây |

## 7. OpenSpec và kiểm tra repository

### SPEC-01 — Validate toàn bộ đặc tả

```powershell
Set-Location D:\KMA\Fluxio
openspec validate --all
```

**Đạt khi:** tất cả spec/change in dấu `✓` và `0 failed`.

### REPO-01 — Kiểm tra trạng thái cuối

```powershell
git status --short
git log -10 --oneline
```

**Đạt khi:** worktree sạch hoặc chỉ có raw evidence/result đã được chủ động tạo trong lần test.

## 8. Thứ tự chạy khuyến nghị

1. `SPEC-01` — bắt lỗi đặc tả sớm.
2. `BE-01` — backend unit/API.
3. `BE-02` — PostgreSQL/MinIO integration.
4. `AND-01` — Android unit.
5. `AND-02` — build APK.
6. `AND-03` — instrumentation trên emulator.
7. `E2E-01` đến `E2E-05`.
8. Benchmark candidate 5/8/16 MiB × 1/2/4.
9. Benchmark acceptance B0–B4.
10. Tổng hợp CSV/JSON/Markdown và chạy lại `SPEC-01`, `REPO-01`.

Nếu một tầng thất bại, dừng các tầng phụ thuộc phía sau, lưu log và sửa nguyên nhân trước khi tiếp tục.

## 9. Biên bản kết quả

Sao chép mẫu này cho mỗi lần chạy:

```markdown
## Test run: YYYY-MM-DD HH:mm Asia/Saigon

- Người chạy:
- Git commit:
- Branch: main
- Host/emulator fingerprint:
- Backend/MinIO/PostgreSQL version:

| Test ID | Bắt đầu | Kết thúc | PASS/FAIL/BLOCKED | Thông số chính | Bằng chứng/log | Ghi chú |
|---|---|---|---|---|---|---|
| SPEC-01 | | | | | | |
| BE-01 | | | | | | |
| BE-02 | | | | | | |
| AND-01 | | | | | | |
| AND-02 | | | | | | |
| AND-03 | | | | | | |
| E2E-01 | | | | | | |
| E2E-02 | | | | | | |
| E2E-03 | | | | | | |
| E2E-04 | | | | | | |
| E2E-05 | | | | | | |
| BENCH-B0 | | | | | | |
| BENCH-B1 | | | | | | |
| BENCH-B2 | | | | | | |
| BENCH-B3 | | | | | | |
| BENCH-B4 | | | | | | |

### Kết luận

- Tổng số kịch bản PASS:
- Tổng số kịch bản FAIL:
- Tổng số kịch bản BLOCKED:
- Candidate part size/concurrency được chọn:
- Các gate chưa đạt:
- Quyết định phát hành: ACCEPT / REJECT / NEEDS RERUN
```

## 10. Quy tắc kết luận

- `PASS`: kịch bản hoàn thành và có bằng chứng đối chiếu được.
- `FAIL`: hệ thống chạy nhưng kết quả sai hoặc vượt ngưỡng.
- `BLOCKED`: không chạy được vì thiếu emulator, Docker, quyền mạng hoặc dependency; không được chuyển thành PASS.
- Không chọn part size/concurrency nếu chưa có đủ 90 lượt candidate đo thực tế.
- Không tuyên bố hiệu năng production, pin, radio hoặc thiết bị vật lý từ số liệu emulator.
