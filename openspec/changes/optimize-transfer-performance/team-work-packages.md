# Phân công nội dung cho nhóm 4 thành viên

> Đề tài: **Tối ưu tải lên và tải xuống tệp trong ứng dụng Android bằng Streaming, Chunking và xử lý bất đồng bộ**  
> Mục đích: phân chia bốn phần nghiên cứu và triển khai độc lập, có thể đánh giá riêng nhưng cùng tạo thành một hệ thống hoàn chỉnh.  
> Cập nhật: 2026-11-23

## 1. Mục tiêu chung

Nhóm xây dựng ứng dụng Android có khả năng tải lên và tải xuống file lớn mà không giữ toàn bộ file trong RAM, có thể chia file thành các phần nhỏ, xử lý bất đồng bộ, tiếp tục sau gián đoạn và đo được hiệu quả tối ưu.

Các chỉ số đánh giá chính:

- Thời gian hoàn thành và throughput.
- Peak memory/heap.
- CPU trong môi trường Android Emulator cố định.
- Số byte phải truyền lại sau lỗi mạng.
- Thời gian phục hồi sau khi mạng trở lại.
- Tỷ lệ hoàn thành và tính đúng đắn của file.

Phạm vi benchmark hiện tại chỉ bao gồm Android Studio Emulator, Good Wi-Fi và Interrupted network profile. Kết quả không được xem là chứng nhận hiệu năng, pin hoặc nhiệt độ trên thiết bị Android thật.

## 2. Nguyên tắc phân chia

Mỗi thành viên sở hữu một câu hỏi nghiên cứu, một nhóm thành phần code, một nhóm thí nghiệm và một phần báo cáo riêng. Các phần dùng chung phải có hợp đồng rõ ràng để tránh phụ thuộc vào code nội bộ của nhau.

| Thành viên | Chuyên đề chính | Câu hỏi nghiên cứu |
|---:|---|---|
| 1 | Streaming và tối ưu bộ nhớ | Streaming trực tiếp giảm RAM và loại bỏ file tạm toàn bộ như thế nào? |
| 2 | Chunking, multipart upload và parallelism | Part size và concurrency ảnh hưởng throughput, CPU, RAM và retry ra sao? |
| 3 | Download Range, resume và mạng gián đoạn | Ranged resume giảm dữ liệu tải lại như thế nào, và segmented download tùy chọn có mang lại lợi ích không? |
| 4 | Xử lý bất đồng bộ, trạng thái và benchmark | WorkManager, Room và state machine giúp transfer sống sót sau process death như thế nào? |

## 3. Thành viên 1 — Streaming và tối ưu bộ nhớ

### 3.1 Mục tiêu

Xây dựng lớp đọc dữ liệu file theo luồng để ứng dụng có thể xử lý file lớn hơn heap mà không tạo bản sao hoàn chỉnh trong RAM hoặc cache.

### 3.2 Nội dung nghiên cứu

- Android `ContentResolver` và `content://` URI.
- `InputStream`, buffer cố định và backpressure.
- Đọc một khoảng byte xác định của file.
- Checksum theo luồng hoặc theo part.
- So sánh whole-file buffering với streaming.
- Ảnh hưởng của buffer size tới memory và throughput.

### 3.3 Thành phần triển khai

- Abstraction nguồn dữ liệu streaming, ví dụ `StreamingSource`.
- Mở lại URI an toàn sau process recreation khi có persistable permission.
- Đọc tuần tự bằng buffer có giới hạn.
- Đọc chính xác một range theo offset và length cho multipart uploader.
- Cập nhật số byte đã đọc/xác nhận.
- Tính checksum mà không nạp toàn bộ part/file vào bộ nhớ.
- Chế độ baseline truyền tuần tự để phục vụ so sánh.

### 3.4 Thí nghiệm phụ trách

| Thí nghiệm | So sánh |
|---|---|
| File 10 MiB, 100 MiB, 1 GiB và 5 GiB | Khả năng xử lý theo kích thước file |
| Whole-file/single-stream baseline với streaming có buffer | Peak heap và thời gian bắt đầu truyền |
| Các buffer size hợp lý | Throughput, CPU và memory |
| File lớn hơn heap ứng dụng | Không OOM và không tạo full temporary copy |

### 3.5 Điểm phối hợp

- Cung cấp API đọc range `(offset, length)` cho multipart uploader của thành viên 2.
- Cung cấp streaming sink/source và checksum dùng chung cho downloader của thành viên 3.
- Phát metric memory/CPU theo schema do thành viên 4 định nghĩa.

### 3.6 Sản phẩm bàn giao

- Module/lớp streaming source có unit test.
- Baseline mode và optimized streaming mode.
- Log/metric peak heap và số byte đã đọc.
- Bảng và biểu đồ file size → peak memory.
- Phần báo cáo giải thích vì sao memory không tăng tuyến tính theo kích thước file.

### 3.7 Tiêu chí hoàn thành

- File 5 GiB có thể được đọc và truyền từng phần mà không OOM.
- Không tồn tại bản sao tạm hoàn chỉnh của file.
- Peak heap nằm trong ngưỡng benchmark đã định nghĩa.
- API streaming có thể được thành viên 2 và 3 sử dụng mà không phụ thuộc implementation nội bộ.

## 4. Thành viên 2 — Chunking, multipart upload và parallelism

### 4.1 Mục tiêu

Xây dựng luồng multipart upload trực tiếp Android → MinIO, hỗ trợ retry từng part và đo ảnh hưởng của part size/concurrency.

### 4.2 Nội dung nghiên cứu

- Chia file thành part dựa trên offset và length.
- Multipart upload của MinIO/S3.
- Presigned PUT URL cho từng part.
- Bounded concurrency.
- ETag, checksum và ordered completion.
- Retry riêng part bị lỗi.
- Quan hệ giữa throughput, CPU, RAM và parallelism.

### 4.3 Thành phần Android

- `ChunkPlanner` hoặc thành phần tương đương.
- `MultipartUploader`.
- Hàng đợi part có concurrency giới hạn.
- Upload part trực tiếp từ streaming range do thành viên 1 cung cấp.
- Thu thập ETag, checksum và số byte xác nhận.
- Không truyền lại part đã được MinIO xác nhận.

### 4.4 Thành phần backend

- `POST /api/v1/uploads`.
- `POST /api/v1/uploads/{uploadId}/part-urls`.
- `GET /api/v1/uploads/{uploadId}/parts`.
- `POST /api/v1/uploads/{uploadId}/complete`.
- `DELETE /api/v1/uploads/{uploadId}`.
- PostgreSQL upload session, quota reservation và idempotency cần thiết cho upload.
- Orchestration MinIO create/list/complete/abort multipart.

### 4.5 Thí nghiệm phụ trách

| Biến độc lập | Giá trị |
|---|---|
| Part size | 5 MiB, 8 MiB, 16 MiB |
| Concurrency | 1, 2, 4 |
| File chính để tuning | 1 GiB |
| Network | Good Wi-Fi và Interrupted |

Đo throughput, CPU, peak memory, retry count, redundant bytes và completion rate. Cấu hình 8 MiB/concurrency 2 chỉ là reference candidate, không phải kết luận trước benchmark.

### 4.6 Sản phẩm bàn giao

- Upload API và multipart orchestration có integration test.
- Android multipart uploader có unit/instrumentation test.
- Kết quả ma trận 3 part size × 3 concurrency.
- Biểu đồ part size/concurrency → throughput, CPU và RAM.
- Phần báo cáo phân tích điểm cân bằng giữa tốc độ và tài nguyên.

### 4.7 Tiêu chí hoàn thành

- Upload hoàn tất với ordered consecutive parts và tạo đúng một file.
- Retry chỉ gửi lại part thiếu/thất bại.
- Số part đang chạy không vượt concurrency cấu hình.
- Complete/idempotency/quota không bị áp dụng hai lần.
- Có bằng chứng lựa chọn part size và concurrency mặc định cho bản demo.

## 5. Thành viên 3 — Download Range, resume và mạng gián đoạn

### 5.1 Mục tiêu

Xây dựng download streaming có thể tiếp tục từ dữ liệu đã ghi bền vững, kiểm tra object version và nghiên cứu segmented download.

### 5.2 Nội dung nghiên cứu

- HTTP Range request.
- Streaming response vào file đích.
- Durable byte offset.
- Object version/ETag.
- Resume sau mất mạng.
- Sequential range so với segmented range.
- Ghi đúng offset và kiểm tra checksum cuối.

### 5.3 Thành phần Android

- `RangeDownloader` hoặc thành phần tương đương.
- Quản lý file tạm và durable offset.
- Resume khi object version không đổi.
- Xóa/thay file tạm và tải lại từ byte 0 khi version thay đổi.
- Pause giữ partial file; cancel xóa partial file.
- Nhánh thí nghiệm segmented download tùy chọn với số segment/concurrency giới hạn.

### 5.4 Thành phần backend

- `POST /api/v1/files/{fileId}/download-ticket`.
- Ownership check và owner-hidden `404`.
- Presigned GET URL hỗ trợ Range.
- Trả file size, object version và thời điểm URL hết hạn.

### 5.5 Thí nghiệm phụ trách

- Download tuần tự không resume làm baseline.
- Streaming ranged download.
- Resume sau hai lần mất mạng, mỗi lần 30 giây.
- Sequential range so với segmented range nếu nhóm chọn thực hiện nhánh tùy chọn trên đích lưu trữ Android.
- Object version thay đổi giữa quá trình download.
- Pause, resume và cancel.

Đo thời gian hoàn thành, CPU, peak memory, redundant bytes, recovery time và checksum kết quả.

### 5.6 Sản phẩm bàn giao

- Range downloader và download-ticket integration.
- Test resume/version/cancel.
- Bảng so sánh download baseline và ranged resume; bổ sung segmented experiment nếu được triển khai.
- Biểu đồ thời gian phục hồi và số byte phải tải lại.
- Phần báo cáo phân tích lợi ích và chi phí của segmented download.

### 5.7 Tiêu chí hoàn thành

- Matching version resume từ durable byte offset.
- Changed version không bao giờ nối byte mới vào partial file cũ.
- Cancel xóa partial file hoặc giữ `CANCELLING` đến khi cleanup xong.
- File cuối có đúng size và checksum.
- Không bắt buộc segmented download; nếu triển khai thì phải có kết luận bằng bằng chứng và không làm chậm nghiệm thu ranged resume bắt buộc.

## 6. Thành viên 4 — Xử lý bất đồng bộ, trạng thái và benchmark

### 6.1 Mục tiêu

Xây dựng orchestration giúp transfer tiếp tục sau process death, thể hiện tiến độ/cancel/retry rõ ràng và tạo bằng chứng benchmark có thể lặp lại.

### 6.2 Nội dung nghiên cứu

- Kotlin Coroutines.
- WorkManager và foreground execution.
- Room persistence.
- State machine.
- Exponential backoff với jitter.
- Process-death recovery.
- Duplicate-work prevention.
- Thu thập và tổng hợp metric benchmark.

### 6.3 Thành phần Android

- Room `transfers` và `transfer_parts`.
- DAO/repository cho durable transfer state.
- WorkManager/foreground worker.
- Điều phối `QUEUED`, `RUNNING`, `PAUSED`, `RECONCILING`, `FAILED`, `CANCELLING`, `CANCELLED`, `COMPLETED` và `REQUIRES_RESELECTION`.
- Progress và retry state cho UI.
- Ngăn nhiều worker cục bộ chạy cùng một transfer.
- Phục hồi upload/download sau khi app bị đóng.

### 6.4 Thành phần backend và hạ tầng

- Cleanup upload `INITIALIZING`/`ACTIVE` hết hạn.
- Reconciliation cho `COMPLETING` bị gián đoạn.
- Công cụ tạo Interrupted network profile.
- Benchmark runner và xuất CSV/JSON.
- Tổng hợp median, p95, completion rate, peak heap, CPU, retry và redundant bytes.

### 6.5 Thí nghiệm phụ trách

- Kill/recreate ứng dụng trong khi upload/download.
- Kill/recreate trong khi `CANCELLING`.
- Mất mạng hai lần và phục hồi tự động.
- Duplicate work request.
- Retryable và non-retryable failure.
- Chạy acceptance matrix trên fixed Android Emulator.

### 6.6 Sản phẩm bàn giao

- Room schema và WorkManager orchestration có test.
- UI/notification thể hiện progress, pause, resume, retry và cancel.
- Benchmark harness có thể chạy lặp lại.
- Raw CSV/JSON và Markdown summary.
- Các biểu đồ tổng hợp của toàn nhóm.
- Phần báo cáo về asynchronous processing và reliability.

### 6.7 Tiêu chí hoàn thành

- Process recreation không làm mất transfer state.
- Transfer luôn reconcile trước khi gửi thêm byte sau restart.
- Không có duplicate local worker.
- `CANCELLING` chỉ thành terminal sau khi cleanup được xác nhận.
- Báo cáo ghi đầy đủ host/emulator configuration và không tuyên bố kết quả thiết bị thật/pin.

## 7. Baseline và các chế độ so sánh

Để chứng minh hiệu quả tối ưu, nhóm cần so sánh các cấp độ thay vì chỉ trình diễn phiên bản cuối.

| Mã | Chế độ | Thành viên chịu trách nhiệm chính |
|---|---|---:|
| `B0` | Ghi toàn bộ file thường vào temporary storage rồi single PUT; không resume | 1 |
| `B1` | Direct streaming single PUT với buffer cố định; không tạo full temp copy | 1 |
| `B2` | Chunking tuần tự, concurrency 1 | 2 |
| `B3` | Chunking song song, concurrency 2 hoặc 4 | 2 |
| `B4` | Chunking + bất đồng bộ + resume khi gián đoạn | 3 và 4 |

Các chế độ phải dùng cùng file, môi trường host/emulator và network profile để kết quả có thể so sánh.

## 8. Hợp đồng phối hợp giữa các thành viên

| Bên cung cấp | Bên sử dụng | Hợp đồng |
|---|---|---|
| Thành viên 1 | Thành viên 2 | Đọc chính xác range `(offset, length)` bằng memory có giới hạn |
| Thành viên 1 | Thành viên 3 | Streaming I/O và checksum không phụ thuộc toàn bộ file |
| Thành viên 2 | Thành viên 4 | Upload progress, part confirmation và retry event |
| Thành viên 3 | Thành viên 4 | Download durable offset, object version và cleanup event |
| Thành viên 4 | Cả nhóm | Room state, worker lifecycle, benchmark format và report template |
| Backend | Android | Hợp đồng trong [`contracts/openapi.yaml`](./contracts/openapi.yaml) |

Thay đổi hợp đồng dùng chung phải được cập nhật trong OpenSpec trước khi hai bên triển khai khác nhau.

## 9. Phần dùng chung

Các thành viên cùng chịu trách nhiệm:

- Review pull request ảnh hưởng hợp đồng chung.
- Giữ `main` hoặc nhánh tích hợp luôn build được.
- Ghi metric theo cùng đơn vị và schema.
- Không dùng dữ liệu benchmark của môi trường khác để so sánh trực tiếp.
- Viết phần giới thiệu, kiến trúc tổng thể, kết luận và hướng phát triển.
- Chuẩn bị kịch bản demo tích hợp.

Không xem login UI, JWT cơ bản, cấu hình MinIO hoặc giao diện trang trí là đóng góp nghiên cứu chính của một thành viên.

## 10. Kịch bản demo tích hợp

1. Chọn file lớn trên Android Emulator.
2. Chạy baseline và ghi nhận throughput/heap/CPU.
3. Chạy streaming multipart với part size/concurrency đã chọn.
4. Hiển thị progress và số part hoàn thành.
5. Ngắt mạng 30 giây rồi cho mạng hoạt động lại.
6. Chứng minh ứng dụng reconcile và không gửi lại confirmed part.
7. Đóng/mở lại ứng dụng và tiếp tục transfer.
8. Download bằng Range và resume từ durable offset.
9. Pause/cancel và kiểm tra cleanup.
10. Xuất báo cáo CSV/JSON và biểu đồ so sánh.

## 11. Biểu đồ trong báo cáo

- File size → peak memory.
- Baseline/streaming → peak memory và thời gian hoàn thành.
- Part size → throughput.
- Concurrency → throughput, CPU và memory.
- Baseline/optimized → completion time.
- Interrupted transfer → redundant bytes.
- Interrupted transfer → recovery time.
- Transfer mode → completion rate.
- Sequential/segmented download → throughput và CPU nếu segmented experiment được triển khai.

## 12. Ma trận trách nhiệm tóm tắt

| Hạng mục | TV1 | TV2 | TV3 | TV4 |
|---|:---:|:---:|:---:|:---:|
| Streaming source/buffer | **Chính** | Dùng | Dùng | Đo |
| Multipart upload | Hỗ trợ I/O | **Chính** | — | Điều phối |
| Upload backend | — | **Chính** | — | Cleanup |
| Range download/segmented tùy chọn | Hỗ trợ I/O | — | **Chính** | Điều phối |
| Download backend | — | — | **Chính** | — |
| Room/WorkManager/state | Hỗ trợ | Hỗ trợ | Hỗ trợ | **Chính** |
| Network interruption/recovery | — | Upload | Download | **Chính** |
| Benchmark harness | Cung cấp metric | Cung cấp metric | Cung cấp metric | **Chính** |
| Phân tích kết quả phần riêng | **Chính** | **Chính** | **Chính** | **Chính** |
| Báo cáo và demo tích hợp | Chung | Chung | Chung | Chung |

## 13. Điều kiện hoàn thành của cả nhóm

- Bốn thành viên đều có code, test, thí nghiệm và phần báo cáo riêng.
- Baseline và optimized mode chạy trên cùng môi trường.
- Upload và download xử lý được file lớn bằng memory có giới hạn.
- Resume hoạt động sau mạng gián đoạn và process recreation.
- File cuối đúng size và checksum.
- Có bảng/biểu đồ chứng minh tác động tới throughput, CPU và RAM.
- Giới hạn của Android Emulator được công bố rõ ràng.
- Mọi OpenSpec scenario có planned evidence trong [`contracts/traceability-matrix.md`](./contracts/traceability-matrix.md).

