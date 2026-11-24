# Ghi chú tiếng Việt — Thành viên 2: Multipart upload

> Đây là bản diễn giải tiếng Việt. Khi triển khai, tài liệu chuẩn vẫn là [plan tiếng Anh](./2026-11-24-fluxio-member-2-multipart-upload.md).

## Mục tiêu

Xây dựng control plane multipart trên Spring Boot và upload engine Android có giới hạn concurrency cho `B2` và `B3`. Android gửi byte trực tiếp tới MinIO; backend chỉ quản lý quyền, quota, trạng thái và presigned URL.

## Kiến trúc và quy tắc

- PostgreSQL là nguồn sự thật cho ownership, quota, idempotency và vòng đời upload.
- AWS SDK v2 S3-compatible điều khiển multipart của MinIO và tạo presigned URL.
- File tối đa 5 GiB; quota mỗi owner 20 GiB.
- Mỗi yêu cầu URL tối đa 32 part hợp lệ và không trùng nhau.
- URL hết hạn sau 15 phút; upload không hoạt động hết hạn sau 24 giờ.
- Completion phải có part liên tiếp từ 1, đúng ETag và tổng số byte.
- Idempotency key dùng theo `(owner, operation, key)`; cùng key khác request trả `409`.
- Owner khác và tài nguyên không tồn tại đều trả `404`.

## Các task

### Task 1 — Schema PostgreSQL và quota nguyên tử

- Tạo Flyway migration cho usage, file, upload session và idempotency.
- Dùng Spring JDBC với row lock/conditional update.
- Chứng minh request đồng thời không thể vượt quota.
- Complete/abort/expire chỉ chuyển hoặc giải phóng reservation đúng một lần.

### Task 2 — Adapter multipart MinIO

- Tạo boundary `MultipartObjectStore` không để SDK type rò ra application layer.
- Hỗ trợ create, presign part, list parts, complete, abort và head object.
- Kiểm thử tích hợp bằng MinIO container.

### Task 3 — API create/sign/list

- `POST /api/v1/uploads`.
- `POST /uploads/{uploadId}/part-urls`.
- `GET /uploads/{uploadId}/parts`.
- Áp dụng request hash, idempotency, ownership và `application/problem+json`.

### Task 4 — Complete, abort, expiry và recovery

- Luồng chính: `ACTIVE -> COMPLETING -> COMPLETED`.
- Nếu process chết sau khi MinIO hoàn tất nhưng DB chưa cập nhật, reconciliation phải hoàn thiện DB đúng một lần.
- Không abort session đang `COMPLETING`; trả `409 UPLOAD_COMPLETION_IN_PROGRESS`.
- Cleanup không được làm quota âm hoặc giải phóng hai lần.

### Task 5 — Android API và ChunkPlanner

- Tạo DTO/Retrofit theo OpenAPI.
- `ChunkPlanner` dùng `Long`, đánh số part từ 1 và chỉ part cuối được ngắn hơn.
- Kiểm thử biên 5 GiB và tránh tràn số.

### Task 6 — Upload engine B2/B3

- `B2`: multipart tuần tự, concurrency 1.
- `B3`: multipart song song có giới hạn 2 hoặc 4.
- Part trên server là nguồn sự thật khi resume.
- Chỉ retry part lỗi; không gửi lại part đã xác nhận.
- Refresh URL hết hạn, tôn trọng `Retry-After`, hỗ trợ pause/cancel.

### Task 7 — Bằng chứng end-to-end

- Upload fixture nhiều part qua presigned URL.
- Mô phỏng restart giữa chừng và resume.
- Complete, download lại, so sánh SHA-256 và kiểm tra quota.

## Sản phẩm bàn giao

- Backend multipart API với PostgreSQL và MinIO.
- Quota/idempotency/recovery có integration test.
- Android `ChunkPlanner` và upload engine B2/B3.
- Bằng chứng completion, resume và cancellation.

