# Tiến độ: Tối ưu hiệu năng truyền file

> Cập nhật lần cuối: 2026-11-24
> Trạng thái change: **Đang triển khai backend control plane**
> Việc tiếp theo: **Triển khai multipart storage boundary và API control plane (2.2)**

## Tổng quan

| Hạng mục | Hoàn thành | Tổng số | Tiến độ | Trạng thái |
|---|---:|---:|---:|---|
| Hoàn thiện đặc tả trước lập trình | 7 | 7 | 100% | Hoàn thành |
| Backend control plane | 1 | 3 | 33% | Đang thực hiện |
| Android streaming upload | 0 | 3 | 0% | Chưa bắt đầu |
| Android resumable download | 0 | 2 | 0% | Chưa bắt đầu |
| Benchmark và nghiệm thu | 0 | 3 | 0% | Chưa bắt đầu |
| **Toàn bộ change** | **8** | **18** | **44%** | **Đang thực hiện** |

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
- [x] Xây dựng ma trận truy vết requirement → API/data contract → test và phê duyệt thiết kế cuối cùng.
  - Ma trận đã tạo: [`contracts/traceability-matrix.md`](./contracts/traceability-matrix.md)
  - Trạng thái: project owner đã phê duyệt ngày 2026-11-24.

### Cổng cho phép lập trình

Cổng đặc tả đã vượt qua: mục 0.5 được phê duyệt, OpenSpec validation thành công và các giới hạn demo đã được khóa.

## Giai đoạn B — Backend control plane

- [x] Tạo PostgreSQL migrations cho file, upload session, idempotency, expiry và quota reservation; `UploadRepositoryIT` đã kiểm tra apply/clean migration, giới hạn 5 GiB, quota 20 GiB, concurrent reservation và terminal accounting exactly-once trên PostgreSQL 17.
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
| Comparative baseline | `B0`–`B4` là bằng chứng bắt buộc |
| Segmented download | Thí nghiệm tùy chọn, không chặn nghiệm thu |

## Tài liệu nguồn

- Phạm vi: [`proposal.md`](./proposal.md)
- Quyết định kiến trúc: [`design.md`](./design.md)
- Phân công nhóm 4 thành viên: [`team-work-packages.md`](./team-work-packages.md)
- Checklist thực thi chi tiết: [`tasks.md`](./tasks.md)
- Delta requirement chính: [`specs/high-performance-file-transfer/spec.md`](./specs/high-performance-file-transfer/spec.md)
- Roadmap triển khai đã phê duyệt: [`../../../docs/superpowers/plans/2026-11-24-fluxio-implementation-roadmap.md`](../../../docs/superpowers/plans/2026-11-24-fluxio-implementation-roadmap.md)
- Plan thành viên 1 — streaming nền tảng: [`../../../docs/superpowers/plans/2026-11-24-fluxio-member-1-streaming.md`](../../../docs/superpowers/plans/2026-11-24-fluxio-member-1-streaming.md)
- Plan thành viên 2 — multipart upload: [`../../../docs/superpowers/plans/2026-11-24-fluxio-member-2-multipart-upload.md`](../../../docs/superpowers/plans/2026-11-24-fluxio-member-2-multipart-upload.md)
- Plan thành viên 3 — resumable download: [`../../../docs/superpowers/plans/2026-11-24-fluxio-member-3-resumable-download.md`](../../../docs/superpowers/plans/2026-11-24-fluxio-member-3-resumable-download.md)
- Plan thành viên 4 — bất đồng bộ và benchmark: [`../../../docs/superpowers/plans/2026-11-24-fluxio-member-4-async-benchmark.md`](../../../docs/superpowers/plans/2026-11-24-fluxio-member-4-async-benchmark.md)

## Quy tắc cập nhật

Khi hoàn thành một việc:

1. Cập nhật bằng chứng hoặc tài liệu tương ứng trước.
2. Chạy validation phù hợp.
3. Đánh dấu cùng một mục trong [`tasks.md`](./tasks.md) và file này.
4. Cập nhật ngày, số lượng hoàn thành, phần trăm và “Việc tiếp theo” ở đầu file.

