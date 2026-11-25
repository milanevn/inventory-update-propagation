# Inventory Update Propagation

[![CI](https://github.com/milanevn/inventory-update-propagation/actions/workflows/ci.yml/badge.svg)](https://github.com/milanevn/inventory-update-propagation/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-336791)

Hệ thống quản lý kho phân tán gồm bốn node, với cơ chế replication tự xây dựng ở tầng ứng dụng: một node ghi, ba chính sách lan truyền, promote thủ công khi primary sập và phục hồi dữ liệu bằng replay.

## Tổng quan

```text
Client ──gRPC──> S1 PRIMARY ──ngay lập tức──> S2 REPLICA ──mỗi n cập nhật──> S4 REPLICA
                     └──────── sau n giây ─────────> S3 REPLICA
```

- **Một node ghi.** Chỉ primary nhận lệnh nhập, xuất kho; dữ liệu và operation log được ghi trong cùng một transaction; operation log đồng thời là hàng chờ gửi, mỗi replica có một con trỏ đến vị trí đã nhận. Mọi node đều phục vụ đọc.
- **Ba chính sách lan truyền:** S2 nhận ngay sau commit, S3 nhận sau tối thiểu n giây, S4 nhận theo lô đủ n cập nhật. Hai tham số đổi được khi hệ thống đang chạy.
- **Giao nhận an toàn khi có lỗi:** at-least-once kết hợp áp dụng idempotent, sắp thứ tự theo `(epoch, seq)`, phát hiện trùng và thiếu sự kiện, tự lấy bù.
- **Thay thế primary có kiểm soát:** lệnh `promote` đưa node có dữ liệu mới nhất lên làm primary; fencing bằng epoch bảo đảm chỉ lịch sử của primary mới được giữ lại, ghi mà primary cũ nhận khi bị cô lập được liệt kê là đã mất.
- **Phục hồi bằng replay:** node có dữ liệu hỏng được dựng lại từ operation log.

Thiết kế chi tiết: [docs/thiet-ke.md](docs/thiet-ke.md).

**Tình trạng triển khai:** các ý trên là thiết kế đích. Hiện mới chạy được `NodeService.GetStatus` và phần nền dùng chung (schema, khóa và cấp `seq`, ghi và áp dụng sự kiện); các RPC còn lại trả `UNIMPLEMENTED`.

## Yêu cầu

- JDK 21
- PostgreSQL 16 ([cách cài](docs/van-hanh.md#1-cài-đặt))
- Windows 10/11 với PowerShell 5.1+

Không cần cài Maven; repository có kèm Maven Wrapper.

## Bắt đầu nhanh

```powershell
git clone https://github.com/milanevn/inventory-update-propagation.git
cd inventory-update-propagation

.\scripts\local-pg.ps1 init          # tạo PostgreSQL cho S1..S4 (5541..5544) và TEST (5549)
.\scripts\doctor.ps1                 # kiểm tra môi trường, không được có dòng FAIL
.\mvnw.cmd -B -ntp clean install     # build, unit test và integration test
.\scripts\run-local.ps1              # chạy S1..S4, chờ đến khi JVM khởi động xong
.\scripts\cluster-status.ps1         # xem trạng thái các node
```

Kết quả mong đợi:

```text
localhost:9101 -> node=S1 role=PRIMARY epoch=1 issued=0 applied=0 status=READY
localhost:9102 -> node=S2 role=REPLICA epoch=1 issued=0 applied=0 status=READY
...
```

Dừng cụm: `.\scripts\stop-local.ps1`, rồi `.\scripts\local-pg.ps1 stop`.

## Sử dụng

S1..S4 lắng nghe gRPC trên cổng 9101..9104; S1 là primary mặc định. Client dòng lệnh in trạng thái (`GetStatus`) của từng node được chỉ định:

```powershell
java -jar cli-client/target/cli-client.jar localhost:9101 localhost:9102
```

Cổng, biến môi trường, quản lý cụm, triển khai trên 4 máy và quy trình xử lý sự cố: [docs/van-hanh.md](docs/van-hanh.md).

## Phát triển

```powershell
.\mvnw.cmd -B -ntp clean verify      # build và chạy toàn bộ test
```

Integration test chạy trên PostgreSQL thật ở instance TEST (cổng 5549); khởi động bằng `.\scripts\local-pg.ps1 start -Node TEST`. Quy trình nhánh, Pull Request, quy ước code và cách chạy từng test: [CONTRIBUTING.md](CONTRIBUTING.md).

## Cấu trúc thư mục

| Thư mục | Nội dung |
|---|---|
| `contracts/` | Hợp đồng gRPC (`.proto`) và stub Java sinh tự động |
| `common-domain/` | Sự kiện domain và port, không phụ thuộc gRPC |
| `node-server/` | Server dùng chung cho S1..S4: gRPC, nghiệp vụ, replication, phục hồi, migration Flyway |
| `cli-client/` | Client dòng lệnh |
| `scripts/` | Script quản lý PostgreSQL, chạy và dừng node, kiểm tra môi trường |

## Tài liệu

| Tài liệu | Đọc khi |
|---|---|
| [docs/thiet-ke.md](docs/thiet-ke.md) | Cần hiểu hệ thống: yêu cầu, kiến trúc, luồng xử lý, giao thức, sự cố và phục hồi, quyết định kỹ thuật |
| [docs/van-hanh.md](docs/van-hanh.md) | Cần cài đặt, chạy cụm, cấu hình, triển khai 4 máy, xử lý sự cố |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Bắt đầu viết code: nhánh, Pull Request, quy ước code, viết và chạy test |

## Công nghệ

Java 21 · Spring Boot (non-web) · gRPC + Protocol Buffers · PostgreSQL 16 · Spring JDBC + Flyway · Maven Wrapper · JUnit 5 · GitHub Actions
