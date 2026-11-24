# Ghi chú tiếng Việt — Thành viên 4: Bất đồng bộ, UI và benchmark

> Đây là bản diễn giải tiếng Việt. Khi triển khai, tài liệu chuẩn vẫn là [plan tiếng Anh](./2026-11-24-fluxio-member-4-async-benchmark.md).

## Mục tiêu

Lưu trạng thái transfer qua process death, điều phối upload/download bằng foreground WorkManager, hiển thị tiến độ/cancel trên Compose và xây dựng benchmark lặp lại được cho B0–B4.

## Kiến trúc và quy tắc

- Room lưu intent và tiến độ cục bộ; server/MinIO vẫn là nguồn sự thật.
- WorkManager điều phối retry, resume và công việc nền.
- Transfer lớn chạy dưới dạng foreground work có notification và cancellation.
- UI quan sát Room thay vì giữ tiến độ tạm trong `MainActivity`.
- Không lưu presigned URL trong Room.
- Upload progress lấy từ part đã xác nhận; download progress từ byte đã ghi bền vững.
- Benchmark chỉ tuyên bố kết quả trên Android Studio Emulator, không tuyên bố pin/thermal/radio/điện thoại thật.

## Các task

### Task 1 — Cấu hình Room/KSP và model

- Bổ sung Room compiler/KSP.
- Tạo entity `transfers` và `transfer_parts` theo data model.
- Tạo converter cần thiết và kiểm thử unique constraint/state mapping.

### Task 2 — DAO và TransferStore

- Tạo DAO cho transfer và part.
- Cung cấp `TransferStore` dùng chung cho upload/download worker.
- Update state/progress theo transaction và không cho transition sai.
- Presigned URL không được xuất hiện trong database.

### Task 3 — Scheduler, worker và foreground notification

- Tạo worker upload/download/cancel/reconcile.
- Phục hồi trạng thái khi app/process được tạo lại.
- Retry có giới hạn, hỗ trợ pause/cancel và foreground notification.
- URI mất quyền chuyển sang `REQUIRES_RESELECTION`.

### Task 4 — Compose transfer UI

- Hiển thị transfer, byte đã truyền/tổng byte, state và retry state.
- Cho phép pause, resume, cancel theo state hợp lệ.
- Không để `MainActivity` trực tiếp làm toàn bộ network I/O như MVP.

### Task 5 — Benchmark recorder và runner B0–B4

- Ghi mode, file size, part size, concurrency, network profile, throughput, peak memory, CPU, retry, reconciliation và completion.
- Thử part size 5/8/16 MiB và concurrency 1/2/4.
- Throughput gate chỉ áp dụng cho Good Wi-Fi.
- Interrupted profile ngắt mạng hai lần, mỗi lần 30 giây; resume không gửi lại part đã xác nhận.

### Task 6 — Tích hợp và bằng chứng nghiệm thu

- Chạy toàn bộ test backend/Android và OpenSpec validation.
- Chạy benchmark trên emulator cố định.
- Tổng hợp raw data và báo cáo so sánh B0 → B4.
- Cập nhật traceability chỉ bằng kết quả thực tế.

## Sản phẩm bàn giao

- Room database và state recovery.
- Foreground WorkManager upload/download.
- UI tiến độ, retry, pause/resume/cancel.
- Benchmark runner/recorder B0–B4.
- Báo cáo throughput, RAM, CPU, retry, resume và completion rate trên emulator.

