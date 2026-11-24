# AGENTS.md — Hướng dẫn cho AI coding agent

File này dành cho AI coding agent (Claude Code, Codex, Copilot, Cursor, ...) làm việc trong repo. Đọc file này trước; khi cần chi tiết, đọc tài liệu được dẫn link bên dưới.

## Bối cảnh

Hệ thống quản lý kho phân tán gồm 4 node S1..S4, replication tự xây dựng ở tầng ứng dụng: một node ghi, ba chính sách lan truyền (S2 ngay, S3 trễ n giây, S4 theo lô n), promote thủ công khi primary sập, phục hồi bằng replay operation log.

| Tài liệu | Dùng khi |
|---|---|
| [docs/thiet-ke.md](docs/thiet-ke.md) | **Nguồn chuẩn về hành vi**: yêu cầu, kiến trúc, luồng xử lý, giao thức, sự cố, quyết định kỹ thuật |
| [docs/van-hanh.md](docs/van-hanh.md) | Cài đặt, chạy cụm, cấu hình, script, xử lý lỗi |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Nhánh, Pull Request, quy ước code, viết và chạy test, Definition of Done |

- Trả lời người dùng và viết tài liệu bằng **tiếng Việt**. Code, tên biến, commit message, `.proto`, SQL và script viết bằng tiếng Anh. File `.ps1` chỉ dùng ký tự ASCII.
- Môi trường chính: Windows, PowerShell 5.1+, JDK 21, PostgreSQL 16 cài trực tiếp; không dùng Docker.

## Cấu trúc repo

| Thư mục | Nội dung |
|---|---|
| `contracts/` | `inventory.proto`, nguồn chuẩn của giao thức; stub Java do Maven sinh |
| `common-domain/` | Sự kiện domain và port (`ReplicationTransport`), không phụ thuộc gRPC |
| `node-server/` | Server của S1..S4; package `config`, `grpc`, `inventory`, `replication`, `policy`, `recovery`; migration Flyway trong `src/main/resources/db/migration` |
| `cli-client/` | Client dòng lệnh |
| `scripts/` | Quản lý PostgreSQL cục bộ, chạy và dừng node, kiểm tra môi trường |

## Quy tắc bắt buộc

1. **Thiết kế là chuẩn.** Hành vi phải khớp `docs/thiet-ke.md`. Không tự đổi quyết định đã chốt (mục 7). Thấy yêu cầu mâu thuẫn với thiết kế thì dừng lại và hỏi người dùng. Đổi hành vi, giao thức hoặc schema thì cập nhật `docs/thiet-ke.md` trong cùng thay đổi.
2. **Một node ghi.** Chỉ primary nhận lệnh ghi; replica từ chối bằng `FAILED_PRECONDITION`. Không thêm cơ chế failover tự động.
3. **Ghi nguyên tử.** Dữ liệu nghiệp vụ, `operation_log`, outbox và `processed_requests` ghi trong cùng một transaction. Trạng thái bền phải nằm trong PostgreSQL, không dựa vào shutdown hook (`stop-local.ps1` tương đương crash).
4. **Hợp đồng.** `contracts/src/main/proto/inventory.proto` là nguồn chuẩn; không sửa code sinh tự động. Thay đổi không tương thích ngược phải hỏi người dùng trước.
5. **Schema.** Không sửa migration đã phát hành; mọi thay đổi là migration mới `V<n>__<mo_ta>.sql`.
6. **Phạm vi.** Chỉ sửa module, package của việc đang làm. Không refactor rộng. Không thêm thư viện, plugin hay công cụ mới khi chưa được đồng ý. Thư viện có trong BOM của Spring Boot hoặc gRPC không ghi version riêng. Không dùng Lombok.
7. **Cấu hình.** Không hardcode cổng, địa chỉ, vai trò node; đọc từ `application.yml` và biến môi trường. Thêm biến môi trường thì cập nhật bảng cấu hình trong `docs/van-hanh.md`.
8. **Không tự thao tác ra bên ngoài.** Không `git push`, merge, tạo Pull Request, đổi cài đặt GitHub, hay xóa dữ liệu PostgreSQL (`local-pg.ps1 reset` / `destroy`) khi chưa được người dùng đồng ý. Không commit thẳng lên `main`.
9. **Không ghi thông tin nhạy cảm.** Không đưa bí mật, đường dẫn máy cá nhân, tên hay thông tin cá nhân của thành viên vào repo.
10. **Tài liệu gọn.** Chỉ có `README.md`, `CONTRIBUTING.md`, `AGENTS.md`, `docs/thiet-ke.md`, `docs/van-hanh.md`; không tạo file tài liệu mới. Không mô tả tính năng chưa chạy như đã chạy.

## Quy ước code

- Java 21; dùng `record` cho dữ liệu bất biến. Log bằng SLF4J, không `System.out` trong server; log replication có `seq` và `event_id`.
- gRPC service mới: Spring bean kế thừa lớp `*ImplBase` sinh từ `.proto`; được đăng ký tự động, không sửa `GrpcServerLifecycle`.
- Mỗi package tự quản lý repository JDBC của mình; lớp trong `grpc` không chứa SQL hay quy tắc nghiệp vụ.
- Test: unit test `*Test`; integration test `*IT` kế thừa `AbstractNodeIT`. Mỗi test tự dọn dữ liệu, không phụ thuộc thứ tự. Chính sách trễ test bằng đồng hồ mô phỏng, không `sleep`. Test của kịch bản nghiệm thu ghi mã `Txx` trong Javadoc.
- Nhánh `feature/<module>-<mo-ta-ngan>` tạo từ `main`. Commit theo Conventional Commits, scope là tên module, ví dụ `feat(replication): apply contiguous event batch`.

## Lệnh thường dùng

```powershell
.\scripts\local-pg.ps1 start                 # bật PostgreSQL S1..S4 và TEST (lần đầu: init)
.\mvnw.cmd -B -ntp clean verify              # build + unit test + integration test
.\mvnw.cmd -pl node-server -am test "-Dtest=NodePropertiesTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd -pl node-server -am verify "-Dit.test=NodeStatusIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false"
.\scripts\run-local.ps1                      # chạy S1..S4, chờ READY (cần build trước)
.\scripts\cluster-status.ps1                 # GetStatus cả 4 node
.\scripts\stop-local.ps1                     # dừng node; rồi .\scripts\local-pg.ps1 stop
```

Cổng: gRPC S1..S4 là 9101..9104; PostgreSQL S1..S4 là 5541..5544; instance TEST cho integration test là 5549. Log node ở `logs\sN.log`. Trong PowerShell, tham số `-D` có dấu chấm phải đặt trong ngoặc kép.

## Trước khi báo hoàn thành

- [ ] `.\mvnw.cmd -B -ntp clean verify` xanh và integration test không bị bỏ qua (log không có `INTEGRATION TESTS SKIPPED`).
- [ ] Nếu sửa code của node: chạy `run-local.ps1` và `cluster-status.ps1` thấy 4 node `READY`.
- [ ] Tài liệu đã cập nhật nếu đổi hành vi, giao thức, schema hoặc cách chạy.
- [ ] Không có `.env`, log, file tạm hay bí mật trong thay đổi; file `.ps1` chỉ chứa ASCII.
- [ ] Báo rõ phần nào đã kiểm chứng bằng lệnh, phần nào chưa.
