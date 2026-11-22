# Hướng dẫn đóng góp

## Thiết lập môi trường phát triển

1. Cài JDK 21 và PostgreSQL 16 theo [docs/van-hanh.md](docs/van-hanh.md#cài-đặt).
2. Khởi tạo cơ sở dữ liệu cục bộ, rồi kiểm tra môi trường; kết quả kiểm tra không được có dòng `FAIL`:

   ```powershell
   .\scripts\local-pg.ps1 init
   .\scripts\doctor.ps1
   ```

3. Build và chạy toàn bộ test. Integration test không được bị bỏ qua:

   ```powershell
   .\mvnw.cmd -B -ntp clean install
   ```

4. Chạy cụm, kiểm tra cả 4 node đều `READY`:

   ```powershell
   .\scripts\run-local.ps1
   .\scripts\cluster-status.ps1
   ```

Cấu hình Git: đặt `user.name` và `user.email` khớp với tài khoản GitHub. Trên Windows, đặt thêm `core.fileMode false`.

## Quy trình làm việc

| Nhánh | Vai trò | Nhận thay đổi từ |
|---|---|---|
| `main` | Phiên bản ổn định: luôn build, test và chạy được cụm 4 node. Dùng để demo và phát hành. | Pull Request từ `develop` |
| `develop` | Nhánh tích hợp: nơi các thành phần được ghép và chạy cùng nhau; có thể tạm thời chưa ổn định. | Pull Request từ nhánh module |
| `module/<ten-module>` | Nhánh làm việc của người phụ trách một module, tạo từ `develop`. `<ten-module>` là tên module hoặc package trong [docs/thiet-ke.md](docs/thiet-ke.md#2-kiến-trúc), trùng với scope của commit message. | Commit của người phụ trách module |

Chỉ có ba loại nhánh trên. Không tạo nhánh khác.

Lý do tách `main` và `develop`: lỗi phát sinh khi ghép các thành phần nằm lại ở `develop`, không làm mất phiên bản đang chạy được; mỗi lần đưa `develop` lên `main` là một mốc rõ ràng, có thể quay lại; khi đóng băng chỉ cần ngừng merge vào `main`.

Nhánh module tồn tại suốt dự án, nên phải tích hợp thường xuyên để tránh xung đột lớn khi ghép:

- Cập nhật `develop` vào nhánh module trước khi bắt đầu một việc mới và trước khi mở Pull Request.
- Mở Pull Request vào `develop` ngay khi xong một việc, không gom nhiều việc vào một Pull Request.

Đưa `develop` lên `main`:

- Maintainer mở Pull Request `develop` → `main` khi các kịch bản nghiệm thu của mốc đều đạt (xem [docs/kiem-thu.md](docs/kiem-thu.md)).
- Merge bằng merge commit, không squash, để hai nhánh không lệch lịch sử.
- Gắn tag `v0.<n>.0` lên commit merge.
- Lỗi phát hiện trên `main` được sửa trên nhánh của module liên quan, merge vào `develop`, rồi đưa lên `main` như trên.

Quy tắc chung:

- Không push trực tiếp lên `main` hoặc `develop`. Mọi thay đổi đi qua Pull Request.
- Pull Request được merge khi có ít nhất một approve và CI thành công. Thay đổi liên quan tới giao thức, schema hoặc quyền ghi của primary cần maintainer duyệt.
- Mỗi Pull Request nên nhỏ và tập trung vào một mục đích, đồng thời liên kết tới issue tương ứng.
- Commit message theo [Conventional Commits](https://www.conventionalcommits.org/), ví dụ `feat(replication): apply contiguous event batch`, `fix(node): fail fast when local database is down`.

## Quy ước kỹ thuật

- **Giao thức:** `contracts/src/main/proto/inventory.proto` là nguồn chuẩn duy nhất. Không chỉnh sửa code sinh tự động. Thay đổi không tương thích ngược phải được thống nhất trước khi merge.
- **Schema:** không sửa migration đã phát hành. Mọi thay đổi schema được viết thành migration mới `V<n>__<mo_ta>.sql`.
- **Cấu trúc package:** đặt code vào đúng package theo bảng trong [docs/thiet-ke.md](docs/thiet-ke.md#2-kiến-trúc).
- **gRPC service:** một service mới chỉ cần là Spring bean kế thừa lớp `*ImplBase` sinh ra từ `.proto`. Service được đăng ký tự động, không sửa `GrpcServerLifecycle`.
- **Cấu hình:** không hardcode địa chỉ, cổng hay vai trò node; mọi giá trị đều lấy từ cấu hình.
- **Logging:** dùng SLF4J. Không dùng `System.out` trong server. Log liên quan tới replication phải có `seq` và `event_id`.
- **Quyết định kiến trúc:** bổ sung vào [docs/thiet-ke.md](docs/thiet-ke.md#5-quyết-định-kiến-trúc).

## Kiểm thử

Unit test đặt tên `*Test`. Integration test đặt tên `*IT` và kế thừa `AbstractNodeIT`. Chi tiết xem [docs/kiem-thu.md](docs/kiem-thu.md).

## Definition of Done

- [ ] `mvnw verify` thành công, bao gồm integration test.
- [ ] Có test cho trường hợp thành công, trùng lặp, lỗi mạng hoặc crash, và các bất biến dữ liệu liên quan.
- [ ] Không có secret trong mã nguồn hoặc cấu hình được commit.
- [ ] Tài liệu được cập nhật khi giao thức, schema hoặc hành vi vận hành thay đổi.
- [ ] Đã chạy thử trên cụm cục bộ và được review.
