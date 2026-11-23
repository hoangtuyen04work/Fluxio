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

- Backend Java 21/Spring Boot 4 cấp JWT và presigned MinIO URL.
- Android hiện vẫn chứa luồng thử nghiệm FXM1, tạo file mã hóa tạm và dùng một PUT/GET toàn file.
- Chưa có PostgreSQL, Room transfer state, multipart upload hoặc resumable transfer.

Canonical spec dưới [`openspec/specs/`](./openspec/specs/) mô tả trạng thái hiện tại cho tới khi active change được triển khai và archive. Không xóa code/spec FXM1 trước khi luồng mới thay thế nó.

### Mục tiêu active change

- Android truyền file trực tiếp với private MinIO; backend không proxy file body.
- Streaming có memory giới hạn, không tạo bản sao toàn bộ file.
- Multipart upload, bounded concurrency, retry từng part và resume.
- Ranged/segmented download và kiểm tra object version.
- Room, WorkManager, progress, pause và cancellation.
- File tối đa 5 GiB; quota 20 GiB mỗi người dùng.
- Benchmark trên Android Studio Emulator với Good Wi-Fi và Interrupted profiles.
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

Luồng chạy hiện tại chỉ dùng để đối chiếu baseline. Hành vi mục tiêu phải theo active OpenSpec change.

