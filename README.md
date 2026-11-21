# Fluxio

Fluxio là ứng dụng Android truyền tệp qua MinIO. Mốc đầu tiên hỗ trợ đăng nhập bằng một tài khoản cấu hình sẵn, tải tệp lên và tải xuống để khôi phục nội dung trên cùng bản cài đặt ứng dụng.

## Chạy thử trên Windows

Cần JDK 21, Docker Desktop và Android Studio với Android SDK 36 và một máy ảo Android (API 26 trở lên).

1. Khởi động MinIO với cổng chỉ mở trên máy chạy emulator.

   ```powershell
   docker run --name fluxio-minio -p 127.0.0.1:9000:9000 -p 127.0.0.1:9001:9001 -e MINIO_ROOT_USER=local-access -e MINIO_ROOT_PASSWORD=local-secret quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z server /data --console-address ":9001"
   ```

   Mở [MinIO Console](http://localhost:9001), đăng nhập bằng thông tin trên và tạo bucket riêng tư tên `fluxio`.

2. Từ thư mục gốc, mở PowerShell khác và chạy backend. Đặt `JAVA_HOME` trỏ đến JDK 21 nếu máy có nhiều bản Java.

   ```powershell
   $env:FLUXIO_PASSWORD = 'doi-thanh-mat-khau-local'
   cd fluxio
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.address=127.0.0.1"
   ```

3. Mở thư mục `Fluxio-Android` trong Android Studio và chạy bản **debug** trên emulator. Đăng nhập với URL mặc định `http://10.0.2.2:8080/`, tên `fluxio` và mật khẩu ở bước 2.
4. Bấm **Select file and upload** và chọn tệp. Khi nhận được mã tệp, bấm **Download and restore**. Đặt tên và phần mở rộng phù hợp với tệp gốc. Có thể so sánh SHA-256 của hai tệp bằng `Get-FileHash` sau khi chép chúng về máy tính.

## Phạm vi mốc đầu tiên

- Android chia tệp thành các khối 1 MiB và mã hóa bằng AES-256-GCM với khóa ngẫu nhiên riêng cho mỗi tệp. Android Keystore bảo vệ khóa dùng để bọc khóa tệp. Khi tải xuống, ứng dụng xác thực từng khối và khối kết thúc trước khi ghi vào tệp đích.
- Tệp mã hóa được ghi tạm trong cache riêng của ứng dụng để gửi bằng một lần PUT. Backend cấp URL ký sẵn và dữ liệu đi trực tiếp giữa Android và MinIO. Mốc này giới hạn mỗi tệp gốc ở 4 GiB và cần đủ dung lượng cache cho tệp mã hóa.
- Ứng dụng nhớ mã tệp tải lên gần nhất cho mỗi tài khoản và máy chủ. Giữ mã của những tệp khác để tải lại. Khôi phục yêu cầu cùng bản cài đặt ứng dụng vì khóa Keystore gắn với bản cài đặt.
- Phiên đăng nhập dùng JWT có thời hạn một giờ. Khởi động lại backend yêu cầu đăng nhập lại. Máy ảo dùng HTTP trong bản debug. Với môi trường khác, cấu hình URL HTTPS và thay thông tin xác thực MinIO qua `FLUXIO_STORAGE_ENDPOINT`, `FLUXIO_STORAGE_ACCESS_KEY`, `FLUXIO_STORAGE_SECRET_KEY` và `FLUXIO_STORAGE_BUCKET`. URL MinIO phải truy cập được từ Android.
- API truyền tệp hiện dùng đường dẫn riêng `/api/v1/mvp/files` và định dạng `FXM1`. Truyền tiếp, multipart theo đặc tả, chia sẻ, và quản lý nhiều tài khoản bằng PostgreSQL thuộc các mốc sau. Xem [đặc tả hệ thống](fluxio-spec/README.md).
