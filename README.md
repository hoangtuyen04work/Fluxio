# Fluxio

Fluxio là đồ án môn **Tối ưu phần mềm di động** nghiên cứu cách áp dụng streaming, chunking và xử lý bất đồng bộ để tăng hiệu năng tải lên/tải xuống file trong Android, giới hạn CPU/RAM và phục hồi khi mạng không ổn định.

## Nguồn tài liệu chính thức

Toàn bộ đặc tả đang sử dụng nằm trong [`openspec/`](./openspec/):

- Hướng thay đổi đang hoạt động: [`optimize-transfer-performance`](./openspec/changes/optimize-transfer-performance/).
- Phạm vi: [`proposal.md`](./openspec/changes/optimize-transfer-performance/proposal.md).
- Kiến trúc: [`design.md`](./openspec/changes/optimize-transfer-performance/design.md).
- Tiến độ: [`progress.md`](./openspec/changes/optimize-transfer-performance/progress.md).
- Phân công nhóm: [`team-work-packages.md`](./openspec/changes/optimize-transfer-performance/team-work-packages.md).
- Công việc triển khai: [`tasks.md`](./openspec/changes/optimize-transfer-performance/tasks.md).
- API và các hợp đồng kỹ thuật: [`contracts/`](./openspec/changes/optimize-transfer-performance/contracts/).

Không sử dụng tài liệu ngoài `openspec/` để quyết định hành vi mới nếu tài liệu đó không được active change tham chiếu.

## Trạng thái hiện tại và mục tiêu

### Code hiện tại

- Backend Java 21/Spring Boot 4 đã có PostgreSQL, quota, multipart control plane, cleanup/reconciliation, download ticket và presigned MinIO URL.
- Android đã có Room transfer state, multipart upload engine, ranged resumable download, WorkManager primitives và benchmark harness, nhưng giao diện hiện vẫn gọi luồng FXM1 cũ nên các engine mới chưa được nối end-to-end.
- Flow B1 đang được bổ sung để người dùng chọn **Upload bình thường** (direct single PUT, không cache toàn file, không resume) hoặc **Upload tối ưu** (multipart + resume) và đo cùng một bộ chỉ số.

Canonical spec dưới [`openspec/specs/`](./openspec/specs/) mô tả trạng thái hiện tại cho tới khi active change được triển khai và archive. Không xóa code/spec FXM1 trước khi luồng mới thay thế nó.

### Mục tiêu active change

- Android truyền file trực tiếp với private MinIO; backend không proxy file body.
- Streaming có memory giới hạn, không tạo bản sao toàn bộ file.
- Multipart upload, bounded concurrency, retry từng part và resume.
- Ranged/segmented download và kiểm tra object version.
- Room, WorkManager, progress, pause và cancellation.
- File tối đa 5 GiB; quota 20 GiB mỗi người dùng.
- Benchmark trên Android Studio Emulator với Good Wi-Fi và Interrupted profiles.
- So sánh B1 và flow tối ưu với cùng file, tài khoản, quota, MinIO và cấu hình mạng; B1 gửi lại từ byte 0 khi gián đoạn.
- Client-side encryption/E2EE được hoãn sang một OpenSpec change khác.

## Cấu trúc dự án

| Đường dẫn | Nội dung |
|---|---|
| [`Fluxio-Android/`](./Fluxio-Android/) | Ứng dụng Android/Kotlin |
| [`fluxio/`](./fluxio/) | Backend Java/Spring Boot |
| [`openspec/`](./openspec/) | Canonical specs, active change và tài liệu thiết kế |

## Chạy MVP hiện tại trên Windows

Cần JDK 21, Docker Desktop và Android Studio với Android SDK 36.

1. Khởi động MinIO:

   ```powershell
   docker run --name fluxio-minio -p 127.0.0.1:9000:9000 -p 127.0.0.1:9001:9001 -e MINIO_ROOT_USER=local-access -e MINIO_ROOT_PASSWORD=local-secret quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z server /data --console-address ":9001"
   ```

   Mở `http://localhost:9001` và tạo private bucket `fluxio`.

2. Chạy backend:

   ```powershell
   $env:FLUXIO_PASSWORD = 'doi-thanh-mat-khau-local'
   cd fluxio
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.address=127.0.0.1"
   ```

3. Mở [`Fluxio-Android/`](./Fluxio-Android/) trong Android Studio và chạy debug trên emulator.
4. Đăng nhập bằng URL `http://10.0.2.2:8080/`, username `fluxio` và mật khẩu đã cấu hình.

### Lấy log Android và số liệu upload

Mỗi lần upload, app ghi nối tiếp hai file trong thư mục app-specific của emulator:

- `android-system.log`: sự kiện app, đăng nhập, bắt đầu/kết thúc upload và lỗi.
- `upload-performance.csv`: mỗi upload là một dòng gồm mode, kích thước file, thời gian, throughput, CPU, heap, network bytes và trạng thái.

Lấy log về máy Windows sau khi chạy:

```powershell
New-Item -ItemType Directory -Force .\benchmark-logs | Out-Null
adb pull /sdcard/Android/data/com.example.fluxio/files/logs/android-system.log .\benchmark-logs\
adb pull /sdcard/Android/data/com.example.fluxio/files/logs/upload-performance.csv .\benchmark-logs\
```

Hai file được append qua nhiều lần chạy. Xóa dữ liệu app hoặc xóa hai file nếu cần bắt đầu một đợt đo mới.

Luồng chạy hiện tại chỉ dùng để đối chiếu baseline. Hành vi mục tiêu phải theo active OpenSpec change.

## Chạy hạ tầng E2E PostgreSQL + MinIO

Docker chỉ chạy hạ tầng; backend vẫn chạy trực tiếp trên Windows để dễ debug.

```powershell
Set-Location D:\KMA\Fluxio
.\scripts\e2e\start-e2e.ps1
.\scripts\e2e\verify-e2e.ps1
```

Hai image cục bộ được build tự động:

- `fluxio-e2e-postgres:17`
- `fluxio-e2e-minio:2025.5.24`

Sau khi hạ tầng healthy, mở terminal khác và chạy backend:

```powershell
Set-Location D:\KMA\Fluxio\fluxio
$env:SPRING_PROFILES_ACTIVE = "e2e"
mvn spring-boot:run
```

| Dịch vụ | Địa chỉ/thông tin |
|---|---|
| Backend từ Android Emulator | `http://10.0.2.2:8080/` |
| PostgreSQL từ backend | `127.0.0.1:5432`, database/user `fluxio` |
| MinIO API từ backend | `http://127.0.0.1:9000` |
| MinIO data plane từ Android | `http://10.0.2.2:9000` |
| MinIO Console | `http://127.0.0.1:9001` |
| Tài khoản ứng dụng | `fluxio` / `fluxio-local-password` |
| MinIO local | `local-access` / `local-secret` |

Dừng hạ tầng và giữ dữ liệu bằng `.\scripts\e2e\stop-e2e.ps1`. Chỉ dùng tham số `-RemoveVolumes` khi chủ động muốn xóa toàn bộ dữ liệu E2E.

