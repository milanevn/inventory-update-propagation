# Vận hành

## 1. Cài đặt

| Thành phần | Phiên bản | Ghi chú |
|---|---|---|
| JDK | 21 | Nếu đặt `JAVA_HOME` thì phải trỏ tới JDK 21 |
| PostgreSQL | 16.x | Bản *binaries* ZIP của EDB (không cần quyền Administrator, không tạo Windows service) hoặc bộ cài chính thức |
| Maven | — | Không cần cài; dùng Maven Wrapper (`mvnw.cmd`, `./mvnw`) |

Script tìm thư mục `bin` của PostgreSQL theo thứ tự: biến `PG_BIN`, `PATH`, rồi `%ProgramFiles%\PostgreSQL\16\bin`. Để đặt `PG_BIN` cố định:

```powershell
[Environment]::SetEnvironmentVariable('PG_BIN', '<thư mục PostgreSQL>\bin', 'User')
```

Sau khi cài, chạy `.\scripts\doctor.ps1`. Script kiểm tra JDK, PostgreSQL, các instance, cổng mạng, artifact build và cấu hình Git, rồi chỉ cách khắc phục từng mục chưa đạt.

## 2. Vận hành cụm cục bộ

Lần chạy đầu tiên: xem [Bắt đầu nhanh](../README.md#bắt-đầu-nhanh) trong README.

| Việc | Lệnh |
|---|---|
| Chạy lại cụm ở lần sau | `.\scripts\local-pg.ps1 start`, rồi `.\scripts\run-local.ps1` |
| Xem trạng thái | `.\scripts\cluster-status.ps1` |
| Xem log | `logs\s1.log` … `logs\s4.log` |
| Dừng một node (giả lập crash) | `.\scripts\stop-local.ps1 -Node S2` |
| Chạy lại một node | `.\scripts\run-local.ps1 -Node S2` |
| Dừng cơ sở dữ liệu của một node | `.\scripts\local-pg.ps1 stop -Node S2` |
| Dừng cả cụm | `.\scripts\stop-local.ps1`, rồi `.\scripts\local-pg.ps1 stop` |
| Xóa dữ liệu và bắt đầu lại | `.\scripts\stop-local.ps1`, `.\scripts\local-pg.ps1 reset -Force`, `.\scripts\run-local.ps1` |

Dữ liệu PostgreSQL nằm ở `%LOCALAPPDATA%\inventory-replication\pg`.

## 3. Cấu hình

| Node | Vai trò mặc định | Cổng gRPC | Cổng PostgreSQL |
|---|---|---|---|
| S1 | PRIMARY | 9101 | 5541 |
| S2, S3, S4 | REPLICA | 9102, 9103, 9104 | 5542, 5543, 5544 |
| TEST | Chỉ cho integration test | — | 5549 |

Biến môi trường của node (mặc định trong `node-server/src/main/resources/application.yml`):

| Biến | Ý nghĩa | Mặc định |
|---|---|---|
| `NODE_ID` | Định danh node | `S1` |
| `NODE_ROLE` | Vai trò ban đầu, `PRIMARY` hoặc `REPLICA`; chỉ dùng khi cơ sở dữ liệu của node còn mới. Sau đó vai trò lưu trong `node_state` | `PRIMARY` |
| `GRPC_PORT` | Cổng gRPC, bind trên mọi interface | `9101` |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | Cơ sở dữ liệu của node | `localhost`, `5541`, `inventory` |
| `DB_USER`, `DB_PASSWORD` | Tài khoản cơ sở dữ liệu | `inventory` / `local_only_change_me` (chỉ dùng cục bộ) |
| `DB_CONNECTION_TIMEOUT_MS` | Thời gian chờ lấy kết nối | `2000` |
| `PEER_S1` … `PEER_S4` | Địa chỉ `host:port` của các node | `localhost:9101` … `localhost:9104` |
| `CLUSTER_TOKEN` | Token dùng chung cho RPC nội bộ (thiết kế, quyết định 7). Chưa được kiểm tra: xác thực chưa được triển khai | — |

`local-pg.ps1` dùng thêm `PG_BIN`, `INVENTORY_PG_DATA` (thư mục dữ liệu) và `DB_PASSWORD`.

Integration test đọc `TEST_DB_HOST`, `TEST_DB_PORT`, `TEST_DB_NAME`, `TEST_DB_USER`, `TEST_DB_PASSWORD`; mặc định trỏ tới instance TEST (`localhost:5549`, database và tài khoản `inventory`, mật khẩu như mặc định của `DB_PASSWORD`). Nếu đặt `DB_PASSWORD` khác khi chạy `local-pg.ps1 init` thì đặt `TEST_DB_PASSWORD` cùng giá trị.

## 4. Script

| Script | Chức năng |
|---|---|
| `doctor.ps1` | Kiểm tra môi trường; chỉ đọc |
| `local-pg.ps1 init\|start\|stop\|status [-Node S1..S4\|TEST]` | Quản lý các PostgreSQL instance độc lập |
| `local-pg.ps1 reset\|destroy [-Node …] [-Force]` | `reset`: xóa và tạo lại database (Flyway migrate lại khi node khởi động). `destroy`: xóa cả thư mục dữ liệu |
| `run-local.ps1 [-Node Sx] [-NoWait]` | Chạy node ở nền, chờ `READY`; node không lên được thì in nguyên nhân và trả mã lỗi 1 |
| `stop-local.ps1 [-Node Sx]` | Dừng node. Trên Windows tương đương crash: shutdown hook không chạy |
| `run-node.ps1 -Id Sx` | Chạy một node ở foreground (dùng khi triển khai nhiều máy); `Ctrl+C` để dừng có kiểm soát |
| `cluster-status.ps1 [host:port …]` | Gọi `GetStatus`; mặc định hỏi 4 node cục bộ |

Các script chạy node đặt sẵn tùy chọn JVM khởi động nhanh (quyết định 8 trong [thiet-ke.md](thiet-ke.md#7-quyết-định-kỹ-thuật)).

## 5. Triển khai trên 4 máy

Mỗi máy chạy một node và một PostgreSQL. PostgreSQL chỉ lắng nghe trên `localhost`; chỉ cổng gRPC được mở ra mạng LAN. gRPC truyền plaintext, nên chỉ triển khai trong mạng LAN tin cậy.

1. **Trên mỗi máy:** checkout cùng một commit, chạy `.\mvnw.cmd -B -ntp clean install`, cài PostgreSQL 16 và khởi tạo instance của node đó, ví dụ `.\scripts\local-pg.ps1 init -Node S2`.
2. **Mở cổng gRPC trên firewall** (cần quyền Administrator, mạng ở chế độ *Private*; gỡ bằng `Remove-NetFirewallRule` khi xong):

   ```powershell
   New-NetFirewallRule -DisplayName "inventory-node S2 gRPC" -Direction Inbound -Protocol TCP -LocalPort 9102 -Action Allow -Profile Private
   ```

3. **Khởi động** theo thứ tự S4, S3, S2, S1, với địa chỉ peer qua biến môi trường:

   ```powershell
   $env:PEER_S1 = '<ip-s1>:9101'
   $env:PEER_S2 = '<ip-s2>:9102'
   $env:PEER_S3 = '<ip-s3>:9103'
   $env:PEER_S4 = '<ip-s4>:9104'
   .\scripts\run-node.ps1 -Id S2
   ```

4. **Kiểm tra** từ máy client: `.\scripts\cluster-status.ps1 <ip-s1>:9101 <ip-s2>:9102 <ip-s3>:9103 <ip-s4>:9104`. Node trả lời qua `localhost` trên chính máy đó nhưng không trả lời từ máy khác thì nguyên nhân là firewall.

## 6. Quy trình xử lý sự cố

Các bước người vận hành thực hiện khi cụm gặp sự cố. Cơ chế phía sau mỗi quy trình được mô tả ở [thiết kế, mục 6](thiet-ke.md#6-sự-cố-và-phục-hồi). Lệnh cụ thể được bổ sung vào từng quy trình cùng Pull Request triển khai tính năng tương ứng.

### 6.1. S1 sập

Xác nhận S1 không còn trả lời, rồi chạy `promote` để đưa node mới nhất trong S2, S3, S4 lên làm primary (thiết kế: [Promote](thiet-ke.md#promote)).

### 6.2. S1 và S2 cùng sập

Như 6.1; ứng viên là node mới nhất trong S3, S4, tuyến lan truyền đổi theo bảng tuyến trong thiết kế.

### 6.3. Primary cũ quay lại

Khởi động lại node như bình thường; node tự chuyển thành replica, in danh sách cập nhật đã mất và đồng bộ lại (thiết kế: [Primary cũ quay lại](thiet-ke.md#primary-cũ-quay-lại)).

### 6.4. Replica hỏng hoặc mất dữ liệu

Node bị cách ly khi kiểm tra bất biến thất bại; phục hồi bằng replay operation log (thiết kế: [Phục hồi dữ liệu](thiet-ke.md#phục-hồi-dữ-liệu)).

### 6.5. Đổi `delaySeconds` hoặc `batchSize` khi đang chạy

Gửi yêu cầu đổi chính sách tới primary; giá trị mới áp dụng cho sự kiện mới và từ lô kế tiếp (thiết kế: [Ba chính sách lan truyền](thiet-ke.md#ba-chính-sách-lan-truyền)).

## 7. Lỗi thường gặp

| Triệu chứng | Nguyên nhân và cách khắc phục |
|---|---|
| `Unable to clean up temporary proto file directory`, thiếu class `*Grpc`, hoặc `Proto path element is not a directory` | Một tiến trình khác (IDE hoặc lệnh Maven khác) đang build vào `contracts/target`. Không chạy đồng thời hai lệnh Maven; trong VS Code, chấp nhận cập nhật cấu hình project khi được hỏi. |
| IDE không nhận diện `NodeServiceGrpc` | Stub chỉ được sinh bởi Maven: chạy `mvnw clean install`, rồi *Java: Clean Java Language Server Workspace*. |
| `clean` không xóa được file `.jar` | Node đang dùng file jar; chạy `stop-local.ps1` trước khi build. |
| Node dừng ngay, log có `Connection to localhost:554x refused` | PostgreSQL của node chưa chạy: `local-pg.ps1 status`, rồi `start`. |
| `FlywayValidateException`, `checksum mismatch` hoặc `Detected applied migration not resolved locally` | Database cục bộ chứa migration từ nhánh khác: làm theo dòng "Xóa dữ liệu và bắt đầu lại" ở mục 2. |
| Node dừng, log có `OutOfMemoryError: Java heap space` | Script giới hạn heap ở `-Xmx256m`. Tăng giá trị trong `run-local.ps1`, `run-node.ps1` và cập nhật quyết định 8. |
| `Cannot bind gRPC port 910x` | Cổng đã bị chiếm; `doctor.ps1` chỉ ra tiến trình đang giữ cổng. |
| Log build có `INTEGRATION TESTS SKIPPED` | Instance TEST chưa chạy: `.\scripts\local-pg.ps1 start -Node TEST`. |
| Cảnh báo `flyway-community-db-support … 401` hoặc `Mockito is currently self-attaching` | Không ảnh hưởng; bỏ qua. |
