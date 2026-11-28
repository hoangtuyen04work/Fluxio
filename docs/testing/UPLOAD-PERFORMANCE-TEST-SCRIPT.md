# Kịch bản kiểm thử hiệu suất upload Fluxio

## 1. Mục tiêu

So sánh hai chế độ upload trên cùng một Android Emulator và cùng một môi trường backend:

- `NORMAL`: upload bình thường bằng single PUT.
- `OPTIMIZED`: upload tối ưu bằng multipart.

Mỗi lần chạy phải tạo được một dòng trong `upload-performance.csv` và các sự kiện tương ứng trong `android-system.log`.

## 2. Điều kiện kiểm thử

- Backend chạy tại `http://10.0.2.2:8080/` từ Android Emulator.
- PostgreSQL và MinIO ở trạng thái healthy.
- Dùng cùng một Android Emulator cho toàn bộ phép đo.
- Không thay đổi cấu hình CPU/RAM của emulator giữa các lần chạy.
- Tắt các ứng dụng không cần thiết trên máy kiểm thử.
- Mạng và backend không được khởi động lại giữa hai chế độ của cùng một nhóm đo.
- Cài bản APK mới nhất có chức năng ghi log.

## 3. Chuẩn bị file dữ liệu

Chuẩn bị các file có nội dung cố định:

| Mã file | Kích thước |
|---|---:|
| F01 | 10 MiB |
| F02 | 50 MiB |
| F03 | 100 MiB |
| F04 | 500 MiB |
| F05 | 1 GiB |

Nếu máy không đủ tài nguyên, có thể bỏ F04 và F05 nhưng phải ghi rõ trong báo cáo.

Ví dụ tạo file trên Windows:

```powershell
New-Item -ItemType Directory -Force D:\KMA\Fluxio\benchmark-data | Out-Null
fsutil file createnew D:\KMA\Fluxio\benchmark-data\file-10MiB.bin 10485760
fsutil file createnew D:\KMA\Fluxio\benchmark-data\file-50MiB.bin 52428800
fsutil file createnew D:\KMA\Fluxio\benchmark-data\file-100MiB.bin 104857600
fsutil file createnew D:\KMA\Fluxio\benchmark-data\file-500MiB.bin 524288000
fsutil file createnew D:\KMA\Fluxio\benchmark-data\file-1GiB.bin 1073741824
```

Đưa file vào emulator bằng Device Explorer hoặc:

```powershell
adb push D:\KMA\Fluxio\benchmark-data /sdcard/Download/fluxio-benchmark
```

## 4. Ma trận chạy

Mỗi trường hợp chạy một lần warm-up, sau đó chạy đo ba lần. Không đưa warm-up vào bảng kết quả chính.

| Test ID | Chế độ | File | Warm-up | Số lần đo |
|---|---|---|---:|---:|
| UP-N-01 | NORMAL | F01 | 1 | 3 |
| UP-O-01 | OPTIMIZED | F01 | 1 | 3 |
| UP-N-02 | NORMAL | F02 | 1 | 3 |
| UP-O-02 | OPTIMIZED | F02 | 1 | 3 |
| UP-N-03 | NORMAL | F03 | 1 | 3 |
| UP-O-03 | OPTIMIZED | F03 | 1 | 3 |
| UP-N-04 | NORMAL | F04 | 1 | 3 |
| UP-O-04 | OPTIMIZED | F04 | 1 | 3 |
| UP-N-05 | NORMAL | F05 | 1 | 3 |
| UP-O-05 | OPTIMIZED | F05 | 1 | 3 |

Chạy xen kẽ `NORMAL` và `OPTIMIZED` theo từng kích thước để giảm sai lệch do môi trường thay đổi theo thời gian.

## 5. Các bước thực hiện

### TC-01 — Kiểm tra kết nối

1. Khởi động PostgreSQL, MinIO và backend.
2. Mở ứng dụng Fluxio trên emulator.
3. Đăng nhập bằng server `http://10.0.2.2:8080/`.
4. Xác nhận ứng dụng hiển thị `Signed in`.

Kết quả mong đợi: đăng nhập thành công và `android-system.log` có sự kiện `sign in succeeded`.

### TC-02 — Upload NORMAL

1. Chọn `Upload bình thường — single PUT`.
2. Chọn file đúng theo dòng trong ma trận.
3. Bắt đầu upload và không thao tác với emulator cho tới khi hoàn tất.
4. Ghi lại Test ID, số lần chạy và thời điểm chạy.
5. Xác nhận ứng dụng hiển thị `Upload complete`.
6. Lặp lại theo số lần quy định.

Kết quả mong đợi:

- File upload thành công và nhận được file ID.
- CSV có dòng với `mode=NORMAL` và `status=SUCCESS`.
- `fileSizeBytes` khớp kích thước file nguồn.
- `durationMillis` và `throughputMbps` lớn hơn 0.

### TC-03 — Upload OPTIMIZED

1. Chọn `Upload tối ưu — multipart`.
2. Chọn cùng file đã dùng cho NORMAL.
3. Bắt đầu upload và không thao tác với emulator cho tới khi hoàn tất.
4. Ghi lại Test ID, số lần chạy và thời điểm chạy.
5. Xác nhận ứng dụng hiển thị `Upload complete`.
6. Lặp lại theo số lần quy định.

Kết quả mong đợi tương tự TC-02, nhưng CSV phải có `mode=OPTIMIZED`.

### TC-04 — Kiểm tra trường hợp lỗi

1. Chọn một file nhỏ.
2. Bắt đầu upload.
3. Tắt backend hoặc ngắt mạng emulator khi upload đang chạy.
4. Chờ ứng dụng thông báo lỗi.
5. Khởi động lại backend/mạng trước khi tiếp tục ma trận chính.

Kết quả mong đợi:

- CSV có một dòng `status=FAILED`.
- `errorType` và `errorMessage` có dữ liệu.
- `android-system.log` có sự kiện `upload finished` với `status=FAILED`.

## 6. Lấy log sau khi chạy

```powershell
Set-Location D:\KMA\Fluxio
New-Item -ItemType Directory -Force .\benchmark-logs | Out-Null

adb pull /sdcard/Android/data/com.example.fluxio/files/logs/android-system.log .\benchmark-logs\
adb pull /sdcard/Android/data/com.example.fluxio/files/logs/upload-performance.csv .\benchmark-logs\
```

Các file kết quả trên Windows:

```text
D:\KMA\Fluxio\benchmark-logs\android-system.log
D:\KMA\Fluxio\benchmark-logs\upload-performance.csv
```

Sao chép và đổi tên file sau mỗi đợt đo để tránh trộn dữ liệu, ví dụ:

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
Copy-Item .\benchmark-logs\upload-performance.csv ".\benchmark-logs\upload-performance-$stamp.csv"
Copy-Item .\benchmark-logs\android-system.log ".\benchmark-logs\android-system-$stamp.log"
```

## 7. Bảng tổng hợp

Với mỗi nhóm `mode + fileSizeBytes`, tính trên ba lần đo chính:

| Chế độ | Kích thước | Số lần thành công | Thời gian TB (ms) | Throughput TB (Mbps) | Peak heap lớn nhất (byte) | CPU TB (%) | Network bytes TB |
|---|---:|---:|---:|---:|---:|---:|---:|
| NORMAL | | | | | | | |
| OPTIMIZED | | | | | | | |

Không tính các dòng warm-up và `FAILED` vào trung bình hiệu suất. Các lần lỗi phải được báo cáo riêng.

## 8. Tiêu chí hoàn tất

- Mỗi dòng trong ma trận có đủ ba lần đo thành công.
- Không thiếu dòng CSV tương ứng với thao tác upload.
- Không có giá trị âm ở thời gian, throughput, CPU hoặc heap.
- Mỗi lần `FAILED` có nguyên nhân trong cả CSV và system log.
- Có thể đối chiếu một lần chạy bằng `runId` giữa hai file log.
- Báo cáo ghi rõ emulator, backend build, ngày chạy và các trường hợp bị loại khỏi phép tính.
