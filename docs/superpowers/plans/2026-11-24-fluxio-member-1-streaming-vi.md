# Ghi chú tiếng Việt — Thành viên 1: Streaming nền tảng

> Đây là bản diễn giải tiếng Việt để học và trao đổi trong nhóm. Khi triển khai, tài liệu chuẩn vẫn là [plan tiếng Anh](./2026-11-24-fluxio-member-1-streaming.md).

## Mục tiêu

Xây dựng lớp I/O nền tảng trên Android để đọc đúng một vùng của file bằng bộ nhớ giới hạn. Phần này cung cấp `RangeSource`, checksum SHA-256 và hai baseline bắt buộc `B0`/`B1` cho các thành viên còn lại.

## Ý nghĩa hai baseline

- `B0_STAGED_SINGLE_PUT`: sao chép toàn bộ file thường vào cache, sau đó upload bằng một PUT. Đây là cách cố tình chưa tối ưu để đo chi phí thời gian và dung lượng tạm.
- `B1_STREAMING_SINGLE_PUT`: đọc trực tiếp từ Content URI bằng buffer cố định rồi thực hiện một PUT. Không tạo bản sao toàn bộ file nhưng chưa thể resume theo part.

Mã hóa bị tắt ở cả hai chế độ để kết quả chỉ phản ánh kiến trúc truyền file.

## Các task

### Task 1 — Interface đọc theo vùng

- Bổ sung Okio, MockWebServer và coroutine test.
- Tạo `RangeSource` với `sizeBytes` và `copyRange(offset, length, sink)`.
- Tạo lỗi `InvalidRange`, `SourceNotSeekable`, `UnexpectedEnd`.
- Dùng `Long` cho mọi offset/kích thước và kiểm tra tràn `offset + length`.

### Task 2 — Đọc Content URI

- Mở URI bằng `ContentResolver.openFileDescriptor`.
- Dùng `FileChannel` để seek tới offset.
- Đọc bằng một buffer mặc định 64 KiB.
- Đọc đúng `length` byte; thiếu byte phải báo `UnexpectedEnd`.
- Kiểm tra hủy coroutine, đóng tài nguyên và provider không hỗ trợ seek.

### Task 3 — SHA-256 streaming

- Cung cấp `RangeSource.sha256(offset, length)`.
- Digest được cập nhật từng chunk, không gom cả vùng vào RAM.
- Kiểm thử vùng rỗng, toàn bộ file và một phần file.

### Task 4 — Request body B0/B1

- B0 phải tạo rồi xóa file cache đầy đủ và ghi nhận thời gian/số byte staging.
- B1 truyền trực tiếp từ `RangeSource` và không tạo file cache.
- Hai chế độ phải gửi byte chính xác, khai báo đúng content length và đóng nguồn khi hủy.

### Task 5 — Bằng chứng

- Tạo `evidence/member-1-streaming.md`.
- Ghi lệnh test, cấu hình emulator, kết quả và giới hạn đã biết.
- Chỉ đổi dòng traceability sang `VERIFIED` sau khi có bằng chứng thật.

## Sản phẩm bàn giao

- `RangeSource` và validation an toàn cho file lớn.
- `ContentUriRangeSource` đọc vùng chính xác bằng buffer giới hạn.
- SHA-256 streaming.
- B0 và B1 có test.
- Instrumentation test trên emulator và tài liệu bằng chứng.

## Phụ thuộc nhóm

- Thành viên 2 dùng `RangeSource` để gửi từng part.
- Thành viên 3 tái sử dụng nguyên tắc buffer giới hạn cho download.
- Thành viên 4 chạy B0/B1 trong benchmark B0–B4.

