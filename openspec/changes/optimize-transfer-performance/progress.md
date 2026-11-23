# Tiến độ: Tối ưu hiệu năng truyền file

> Cập nhật lần cuối: 2026-11-22  
> Trạng thái change: **Đang hoàn thiện đặc tả, chưa bắt đầu lập trình**  
> Việc tiếp theo: **Xây dựng ma trận truy vết requirement → contract → test và phê duyệt thiết kế (0.5)**

## Tổng quan

| Hạng mục | Hoàn thành | Tổng số | Tiến độ | Trạng thái |
|---|---:|---:|---:|---|
| Hoàn thiện đặc tả trước lập trình | 6 | 7 | 86% | Đang thực hiện |
| Backend control plane | 0 | 3 | 0% | Chưa bắt đầu |
| Android streaming upload | 0 | 3 | 0% | Chưa bắt đầu |
| Android resumable download | 0 | 2 | 0% | Chưa bắt đầu |
| Benchmark và nghiệm thu | 0 | 3 | 0% | Chưa bắt đầu |
| **Toàn bộ change** | **6** | **18** | **33%** | **Đang thực hiện** |

Quy ước:

- `[x]` Đã hoàn thành và đã có tài liệu hoặc bằng chứng tương ứng.
- `[ ]` Chưa hoàn thành.
- Các mục triển khai chỉ được bắt đầu sau khi hoàn tất cổng đặc tả.

## Giai đoạn A — Hoàn thiện đặc tả

### Phạm vi và quyết định sản phẩm

- [x] Chốt giới hạn tối đa **5 GiB cho mỗi file**.
- [x] Chốt quota **20 GiB cho mỗi người dùng** và loại bỏ dữ liệu FXM1 thử nghiệm.

### Hợp đồng kỹ thuật

- [x] Định nghĩa OpenAPI, DTO, mã trạng thái HTTP, ownership và idempotency.
  - Bằng chứng: [`contracts/openapi.yaml`](./contracts/openapi.yaml)
  - Bằng chứng: [`contracts/api-contract.md`](./contracts/api-contract.md)
- [x] Định nghĩa mô hình PostgreSQL, Room, quota transaction, indexes và recovery.
  - Bằng chứng: [`contracts/data-model.md`](./contracts/data-model.md)
- [x] Định nghĩa bảng chuyển trạng thái của upload phía server và transfer phía Android.
  - Bằng chứng: [`contracts/state-machines.md`](./contracts/state-machines.md)
- [x] Định nghĩa benchmark protocol và các ngưỡng nghiệm thu bằng số cho môi trường Android Emulator.
  - Bằng chứng: [`contracts/benchmark-protocol.md`](./contracts/benchmark-protocol.md)
  - Phạm vi đã chốt: Good Wi-Fi và Interrupted; throughput chỉ nghiệm thu trên Good Wi-Fi; đo CPU nhưng không đo pin.
- [ ] Xây dựng ma trận truy vết requirement → API/data contract → test và phê duyệt thiết kế cuối cùng.
  - Ma trận đã tạo: [`contracts/traceability-matrix.md`](./contracts/traceability-matrix.md)
  - Trạng thái: đang chờ project owner phê duyệt thiết kế.

### Cổng cho phép lập trình

Chỉ chuyển sang Giai đoạn B khi cả ba mục còn lại của Giai đoạn A được đánh dấu hoàn thành, toàn bộ OpenSpec validation thành công, và không còn quyết định hành vi ảnh hưởng tới implementation.

## Giai đoạn B — Backend control plane

- [ ] Tạo PostgreSQL migrations cho file, upload session, idempotency, expiry và quota reservation; kiểm tra apply/rollback migration.
- [ ] Triển khai create upload, sign parts, list parts, complete và abort; bổ sung contract tests.
- [ ] Triển khai cleanup cho upload hết hạn; xác minh cả MinIO multipart và database session được xử lý.

## Giai đoạn C — Android streaming upload

- [ ] Tạo Room state cho transfer/part và xử lý persisted URI permission.
- [ ] Triển khai multipart streaming với memory và concurrency được giới hạn.
- [ ] Triển khai reconciliation, retry, cancellation và chống chạy trùng transfer.

## Giai đoạn D — Android resumable download

- [ ] Triển khai ranged download continuation có kiểm tra object version.
- [ ] Triển khai foreground progress, retry state và cancellation hiển thị cho người dùng.

## Giai đoạn E — Benchmark và nghiệm thu

- [ ] Xây dựng benchmark matrix lặp lại được trên Android Emulator cố định cho bốn kích thước file và hai profile Good Wi-Fi/Interrupted.
- [ ] Đo part size 5/8/16 MiB với concurrency 1/2/4; chọn default bằng bằng chứng.
- [ ] Chạy end-to-end upload, resume, complete, download, cancel và cleanup; đối chiếu tất cả OpenSpec scenarios.

## Quyết định đã khóa

| Quyết định | Giá trị hiện tại |
|---|---|
| Trọng tâm release | Hiệu năng truyền file và khả năng resume |
| Kích thước file tối đa | 5 GiB |
| Quota mỗi người dùng | 20 GiB |
| Data plane | Android truyền trực tiếp với private MinIO bằng presigned URL |
| Control plane | Spring Boot modular monolith |
| Metadata | PostgreSQL |
| Trạng thái cục bộ Android | Room |
| FXM1 test data | Xóa, không xây migration/compatibility path |
| E2EE và client-side encryption | Hoãn sang OpenSpec change trong tương lai |
| Microservices, Kafka, Redis | Không sử dụng nếu chưa có bằng chứng cần thiết |

## Tài liệu nguồn

- Phạm vi: [`proposal.md`](./proposal.md)
- Quyết định kiến trúc: [`design.md`](./design.md)
- Phân công nhóm 4 thành viên: [`team-work-packages.md`](./team-work-packages.md)
- Checklist thực thi chi tiết: [`tasks.md`](./tasks.md)
- Delta requirement chính: [`specs/high-performance-file-transfer/spec.md`](./specs/high-performance-file-transfer/spec.md)

## Quy tắc cập nhật

Khi hoàn thành một việc:

1. Cập nhật bằng chứng hoặc tài liệu tương ứng trước.
2. Chạy validation phù hợp.
3. Đánh dấu cùng một mục trong [`tasks.md`](./tasks.md) và file này.
4. Cập nhật ngày, số lượng hoàn thành, phần trăm và “Việc tiếp theo” ở đầu file.

