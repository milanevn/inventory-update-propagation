# Kiểm thử

## 1. Nguyên tắc

- Mỗi thay đổi đi kèm unit test hoặc integration test tương ứng.
- Test phải chạy lặp lại được và cho kết quả tất định; không phụ thuộc thứ tự chạy.
- Build thành công và kịch bản nghiệm thu thành công là hai tiêu chí độc lập.

## 2. Quy ước viết test

| Loại | Quy ước tên | Thực thi | Cơ sở dữ liệu |
|---|---|---|---|
| Unit test | `*Test.java` | Surefire, trong `mvnw verify` | Không sử dụng |
| Integration test | `*IT.java`, kế thừa `AbstractNodeIT` | Failsafe, trong `mvnw verify` | Instance `TEST` (`localhost:5549`); CI dùng service `postgres:16` trên cùng cổng |

- `AbstractNodeIT` khởi động node thật (S1, vai trò PRIMARY, gRPC trên cổng ngẫu nhiên) và thực hiện Flyway `clean` + `migrate` mỗi khi tạo Spring context. Ví dụ tham khảo: `NodeStatusIT`.
- Mỗi test tự chuẩn bị và dọn dẹp dữ liệu riêng.
- Integration test không được kết nối tới cơ sở dữ liệu của S1..S4 (cổng 5541..5544).
- Khi instance `TEST` không khả dụng, integration test được bỏ qua kèm cảnh báo khi chạy cục bộ. Trên CI (`CI=true`), trường hợp này làm build thất bại.
- Chính sách trễ được unit test bằng đồng hồ mô phỏng, không dùng `sleep`.

## 3. Kịch bản nghiệm thu

Đây là danh sách kịch bản cần đạt, chưa phải bằng chứng đã chạy. Hiện mới có T18 (test tự động `ProtoContractTest`) và phần khởi động độc lập của T16 (kiểm tra thủ công bằng `stop-local.ps1`/`local-pg.ps1`). Các kịch bản còn lại phụ thuộc vào phần nghiệp vụ và replication chưa được triển khai.

Mức **Bắt buộc**: phải đạt trước khi phát hành. Mức **Nên có**: thực hiện sau khi các kịch bản Bắt buộc đã đạt.

| ID | Mức | Kịch bản | Kết quả mong đợi |
|---|---|---|---|
| T01 | Bắt buộc | Tạo sản phẩm, nhập, xuất kho trên S1 | Dữ liệu nghiệp vụ, log, outbox và kết quả request được commit nguyên tử; lệnh lỗi được rollback toàn bộ |
| T02 | Bắt buộc | S2 ngừng khi S1 commit | Client vẫn nhận kết quả thành công; outbox lưu bền sự kiện chờ gửi |
| T03 | Bắt buộc | Lan truyền S1 → S2 bình thường | S2 bắt kịp S1; sự kiện trùng không được áp dụng hai lần |
| T04 | Bắt buộc | `delaySeconds = 5` | Không gửi trước thời điểm đến hạn; gửi sau thời điểm đến hạn |
| T05 | Bắt buộc | `batchSize = 3` | Sau 1–2 sự kiện, S4 chưa thay đổi; sau 3 sự kiện, cả lô được áp dụng; phần còn lại tiếp tục chờ |
| T06 | Bắt buộc | Trùng `event_id` hoặc trùng `seq` | Cùng nội dung: bỏ qua; khác nội dung: `CONFLICT` |
| T07 | Bắt buộc | Nhận `seq` 5 khi tiến độ là 3 | `GAP_DETECTED`, trạng thái không đổi, lấy bổ sung `seq` 4 |
| T08 | Bắt buộc | Phân vùng mạng S1 ↔ S2 | S2 bắt kịp sau khi kết nối được khôi phục |
| T09 | Bắt buộc | Crash sau khi S2 commit, trước khi gửi ACK | Retry từ S1 không làm nhân đôi tồn kho |
| T10 | Bắt buộc | Khởi động lại S2 khi có lô S4 đang chờ | Không mất, không đếm trùng; thành viên lô giữ nguyên |
| T11 | Bắt buộc | Xuất kho đồng thời vượt tồn kho | Tồn kho không âm |
| T12 | Bắt buộc | Mất cơ sở dữ liệu của S3; hoặc sửa sai tồn kho tại S3 | Mất DB: S3 replay toàn bộ log và khớp với nguồn. Sửa sai: kiểm tra bất biến phát hiện được, S3 bị cách ly, replay và khớp lại |
| T13 | Bắt buộc | S1 sập khi cập nhật mới nhất chưa đến replica nào, rồi chạy `promote` | Trước khi promote: ghi bị từ chối, đọc ở replica vẫn chạy. Sau khi promote: node mới nhất (thường là S2) thành primary với epoch 2, nhận ghi tiếp; các replica còn lại bắt kịp |
| T14 | Bắt buộc | S1 và S2 cùng sập, rồi chạy `promote` | Node mới nhất trong S3, S4 thành primary, nhận ghi; node còn lại nhận theo tuyến mới |
| T15 | Bắt buộc | Primary cũ hoạt động trở lại sau khi đã promote | Primary cũ tự chuyển thành replica, không nhận ghi; các cập nhật chưa kịp nhân bản bị loại bỏ và được liệt kê; dữ liệu khớp với primary mới sau khi replay |
| T16 | Bắt buộc | 4 node và 4 cơ sở dữ liệu trên một máy | Dừng được từng node một cách độc lập |
| T17 | Nên có | 4 máy vật lý trong LAN | Giao tiếp qua mạng, không phụ thuộc `localhost` |
| T18 | Bắt buộc | Hợp đồng Protobuf | Service descriptor khớp đặc tả |
| T19 | Bắt buộc | Thay đổi `delaySeconds`/`batchSize` | Chỉ áp dụng cho sự kiện mới; lô đang gửi giữ nguyên |
| T20 | Nên có | RPC nội bộ thiếu hoặc sai token | Bị từ chối (`UNAUTHENTICATED`), dữ liệu không thay đổi |
| T21 | Bắt buộc | S3 hoặc S4 sập trong lúc S1 tiếp tục nhận ghi, rồi hoạt động trở lại | S1, S2 và node còn lại hoạt động bình thường; node quay lại bắt kịp đầy đủ, không mất và không trùng |
| T22 | Bắt buộc | Gửi lệnh ghi tới primary cũ sau khi đã promote | Bị từ chối (`FAILED_PRECONDITION`); sự kiện từ epoch cũ bị replica từ chối (`STALE_EPOCH`) |

## 4. Chèn lỗi

| Lỗi | Cách tạo |
|---|---|
| Crash node | `.\scripts\stop-local.ps1 -Node Sx` |
| Mất cơ sở dữ liệu | `.\scripts\local-pg.ps1 stop -Node Sx` |
| Mất mạng | Chặn traffic giữa các node trong LAN |
| Mất phản hồi | Adapter transport trong test cố ý bỏ phản hồi |

Không mô phỏng split-brain bằng cách chỉ thay đổi cờ vai trò trong bộ nhớ.

Bằng chứng nghiệm thu cho mỗi kịch bản gồm:
- lệnh đã thực thi;
- `(epoch, seq)` và tồn kho tại S1..S4, trước và sau khi chạy;
- kết quả RPC;
- đối chiếu kết quả mong đợi với kết quả thực tế.

## 5. Test hiện có

| Test | Phạm vi |
|---|---|
| `ProtoContractTest` | Service descriptor của hợp đồng gRPC (T18) |
| `NodePropertiesTest` | Kiểm tra cấu hình node |
| `GrpcServerLifecycleTest` | Vòng đời gRPC server, đăng ký service tự động, phát hiện đăng ký trùng |
| `NodeStatusIT` | Migration Flyway và `GetStatus` qua gRPC trên PostgreSQL thật |
