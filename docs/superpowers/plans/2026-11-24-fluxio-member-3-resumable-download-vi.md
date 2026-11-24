# Ghi chú tiếng Việt — Thành viên 3: Resumable download

> Đây là bản diễn giải tiếng Việt. Khi triển khai, tài liệu chuẩn vẫn là [plan tiếng Anh](./2026-11-24-fluxio-member-3-resumable-download.md).

## Mục tiêu

Xây dựng download streaming tuần tự có thể tiếp tục bằng HTTP Range, kiểm tra object version trước khi nối dữ liệu và dọn file tạm đúng khi hủy. Segmented download chỉ là thí nghiệm tùy chọn.

## Kiến trúc và quy tắc

- Backend kiểm tra ownership và trả download ticket ngắn hạn.
- Byte đi trực tiếp từ MinIO tới Android, không qua Spring Boot.
- Android ghi vào destination có khả năng seek/truncate.
- Chỉ lưu tiến độ sau khi byte đã được ghi bền vững.
- Resume chỉ khi object version/ETag hiện tại giống version đã lưu.
- Nếu server bỏ qua Range và trả `200`, phải restart an toàn, không append.
- Nếu version thay đổi, xóa/truncate phần cũ và tải lại từ byte 0.
- Hủy phải xóa phần dữ liệu chưa hoàn chỉnh; nếu chưa xóa được thì giữ `CANCELLING`.

## Các task

### Task 1 — Download ticket backend

- Triển khai `POST /api/v1/files/{fileId}/download-ticket`.
- Tài nguyên không tồn tại, đã xóa hoặc không thuộc owner đều trả `404`.
- Response gồm URL, expiry, size, content type, object version, ETag và metadata Range.

### Task 2 — Android contract và destination seekable

- Tạo Retrofit DTO/API.
- Tạo `DownloadDestination` với `openAt`, `truncate`, `durablePosition`, `delete`.
- Provider không hỗ trợ seek/truncate phải buộc restart thay vì append sai.

### Task 3 — Sequential streaming bắt buộc

- Tải mới chấp nhận `200` từ offset 0.
- Resume yêu cầu và xác minh `206 Content-Range`.
- Kiểm tra malformed range, body ngắn, `416`, URL hết hạn và retry.
- Dùng buffer cố định, không giữ toàn bộ file trong RAM.

### Task 4 — Resume theo version và cancellation recovery

- Quyết định gồm `RESUME`, `RESTART_VERSION_CHANGED`, `RESTART_DESTINATION_UNSEEKABLE`, `REQUIRES_RESELECTION`.
- Không ghi byte trước khi version và Range được xác nhận.
- Sau process death, trạng thái `CANCELLING` tiếp tục dọn file tới khi xác nhận `CANCELLED`.

### Task 5 — Bằng chứng tích hợp

- Download ít nhất 100 MiB trên emulator.
- Ngắt giữa chừng, tái tạo components rồi resume.
- Kiểm tra size/SHA-256 và chứng minh prefix đã xác nhận không bị tải lại.
- Kiểm tra version change và cancellation.

### Task 6 — Segmented download tùy chọn

- Chỉ làm sau khi sequential ranged download đạt yêu cầu.
- Các range không chồng lấn, ghi đúng offset, concurrency có giới hạn.
- Nếu không cải thiện throughput hoặc không triển khai, release vẫn hợp lệ.

## Sản phẩm bàn giao

- Download ticket endpoint.
- Sequential streaming có Range resume.
- Bảo vệ không trộn byte của hai object version.
- Cancellation cleanup có recovery.
- Bằng chứng trên Android Emulator.

