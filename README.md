# Inventory Update Propagation

Hệ thống quản lý nhập, xuất và tồn kho chạy trên bốn node độc lập. Các node đồng bộ với nhau bằng cơ chế lan truyền cập nhật được xây dựng ở tầng ứng dụng.

```text
Client ──gRPC──> S1 PRIMARY ──ngay lập tức──> S2 REPLICA ──mỗi n cập nhật──> S4 REPLICA
                     └──────── sau n giây ─────────> S3 REPLICA
```

> **Trạng thái: sẵn sàng phát triển, chưa demo được nghiệp vụ.** Đã có: hợp đồng gRPC, schema, hạ tầng chạy 4 node độc lập, `NodeService.GetStatus` và hạ tầng kiểm thử. Nghiệp vụ kho, replication và phục hồi **chưa được triển khai**. Bốn node khởi động và trả `READY` chỉ chứng minh node chạy và kết nối được cơ sở dữ liệu, **chưa** chứng minh dữ liệu được đồng bộ. Chi tiết ở mục [Trạng thái tính năng](#trạng-thái-tính-năng).

## Mục tiêu thiết kế

Các mục dưới đây là thiết kế mục tiêu, đặc tả trong [docs/thiet-ke.md](docs/thiet-ke.md):

- **Một node ghi duy nhất (single-writer).** Mọi thao tác ghi đi qua primary. Mỗi giao dịch ghi dữ liệu nghiệp vụ, operation log và outbox trong cùng một transaction PostgreSQL.
- **Ba chính sách lan truyền, cấu hình được:**
  - S1 → S2: gửi ngay sau khi commit;
  - S1 → S3: gửi sau tối thiểu *n* giây;
  - S2 → S4: gửi theo lô đủ *n* cập nhật.
- **Giao nhận an toàn khi có lỗi.** At-least-once kết hợp apply idempotent; sự kiện sắp thứ tự theo `(epoch, seq)`; phát hiện trùng lặp và thiếu sự kiện.
- **Thay thế primary có kiểm soát.** Khi S1 sập (hoặc S1 và S2 cùng sập), một lệnh `promote` đưa node còn hoạt động có dữ liệu mới nhất lên làm primary; fencing bằng epoch đảm bảo không bao giờ có hai node cùng nhận ghi. S3 hoặc S4 sập không ảnh hưởng tới hoạt động của hệ thống.
- **Phục hồi dữ liệu bằng replay.** Node có dữ liệu bị tổn hại được dựng lại từ operation log của node nguồn.
- **Hai mô hình triển khai, một mã nguồn:** bốn node trên một máy, hoặc bốn máy vật lý trong mạng LAN.

Replication được xây dựng hoàn toàn trong ứng dụng; không sử dụng streaming hay logical replication của PostgreSQL.

## Công nghệ

Java 21 · Spring Boot (non-web) · gRPC + Protocol Buffers · PostgreSQL 16 · Spring JDBC + Flyway · Maven (wrapper) · JUnit 5 · GitHub Actions

## Yêu cầu hệ thống

- JDK 21
- PostgreSQL 16 (bản server binaries hoặc bộ cài; tùy chọn: Docker)
- Windows 10/11 với PowerShell 5.1+, hoặc Linux/macOS với Bash

Không cần cài Maven; repository có kèm Maven Wrapper.

## Bắt đầu nhanh

```powershell
.\scripts\local-pg.ps1 init          # khởi tạo PostgreSQL cho S1..S4 (5541..5544) và TEST (5549)
.\scripts\doctor.ps1                 # kiểm tra môi trường
.\mvnw.cmd -B -ntp clean install     # build, unit test và integration test
.\scripts\run-local.ps1              # chạy S1..S4 (gRPC 9101..9104), chờ đến khi READY
.\scripts\cluster-status.ps1         # truy vấn trạng thái các node
```

Kết quả mong đợi:

```text
localhost:9101 -> node=S1 role=PRIMARY epoch=1 issued=0 applied=0 status=READY
localhost:9102 -> node=S2 role=REPLICA epoch=1 issued=0 applied=0 status=READY
...
```

Dừng cụm: `.\scripts\stop-local.ps1`, rồi `.\scripts\local-pg.ps1 stop`. Hướng dẫn cài đặt PostgreSQL, triển khai trên nhiều máy và xử lý sự cố có trong [docs/van-hanh.md](docs/van-hanh.md).

## Cấu trúc repository

| Đường dẫn | Nội dung |
|---|---|
| `contracts/` | Hợp đồng gRPC (`.proto`) và stub Java sinh tự động |
| `common-domain/` | Mô hình domain và port, độc lập với tầng transport |
| `node-server/` | Server dùng chung cho S1..S4: gRPC, nghiệp vụ, replication, recovery, migration Flyway |
| `cli-client/` | Client dòng lệnh |
| `scripts/` | Script vận hành: PostgreSQL cục bộ, chạy và dừng node, kiểm tra môi trường |
| `deploy/` | Docker Compose cho PostgreSQL (tùy chọn) |
| `docs/` | Tài liệu thiết kế, kiểm thử và vận hành |

## Trạng thái tính năng

| Thành phần | Trạng thái |
|---|---|
| Khởi chạy 4 node độc lập, mỗi node một PostgreSQL; `NodeService.GetStatus` | Hoàn thành |
| Hạ tầng kiểm thử (unit test, integration test với PostgreSQL), CI | Hoàn thành |
| Hợp đồng gRPC v1, schema và migration V1 | Hoàn thành; có bổ sung dự kiến, xem [Hạn chế đã biết](docs/thiet-ke.md#6-hạn-chế-đã-biết) |
| Nghiệp vụ nhập, xuất, tồn kho (`InventoryService`) | Chưa triển khai; mới có hợp đồng |
| Replication engine, ba chính sách lan truyền (`ReplicationService`) | Chưa triển khai; mới có hợp đồng |
| Thay đổi chính sách khi đang chạy (`NodeService.UpdatePolicy`) | Chưa triển khai; mới có hợp đồng |
| Kết nối giữa các node (`cluster.peers`) | Chưa triển khai; mới khai báo cấu hình |
| Phục hồi dữ liệu, catch-up, thay thế primary (`promote`) | Chưa triển khai |
| Xác thực RPC nội bộ | Chưa triển khai |
| Docker Compose, triển khai trên 4 máy vật lý | Đã có cấu hình và hướng dẫn; chưa kiểm thử |

## Tài liệu

- [Thiết kế hệ thống](docs/thiet-ke.md): yêu cầu, kiến trúc, giao thức, phục hồi, quyết định kiến trúc
- [Kiểm thử](docs/kiem-thu.md): kịch bản nghiệm thu T01–T22, quy ước viết test
- [Vận hành](docs/van-hanh.md): cài đặt, cấu hình, triển khai, xử lý sự cố
- [Hướng dẫn đóng góp](CONTRIBUTING.md)

Dự án được phát triển trong khuôn khổ học phần Các hệ thống phân tán.
