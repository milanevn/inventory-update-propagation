# Vận hành

## Cài đặt

| Thành phần | Phiên bản | Ghi chú |
|---|---|---|
| JDK | 21 | Bắt buộc. Nếu đặt `JAVA_HOME` thì phải trỏ tới JDK 21 |
| Maven | — | Không cần cài; dùng Maven Wrapper (`mvnw.cmd`, `./mvnw`) |
| PostgreSQL | 16.x | Bản server binaries (ZIP) hoặc bộ cài chính thức. Docker là tùy chọn |

**PostgreSQL trên Windows.** Có hai cách:
- Dùng bản *binaries* ZIP chính thức của EDB: không cần quyền Administrator và không tạo Windows service. Thư mục `pgAdmin 4` trong gói ZIP không được dự án sử dụng và có thể xóa.
- Dùng bộ cài chính thức.

Script tìm thư mục `bin` của PostgreSQL theo thứ tự:
1. biến môi trường `PG_BIN`;
2. `PATH`;
3. `%ProgramFiles%\PostgreSQL\16\bin`.

```powershell
[Environment]::SetEnvironmentVariable('PG_BIN', '<thư mục PostgreSQL>\bin', 'User')
```

Sau khi cài đặt, chạy `.\scripts\doctor.ps1`. Script này kiểm tra JDK, PostgreSQL, các instance, cổng mạng, artifact build và cấu hình Git, rồi đưa ra hướng khắc phục cho từng mục chưa đạt.

## Cấu hình

| Node | Vai trò | Cổng gRPC | Cổng PostgreSQL |
|---|---|---|---|
| S1 | PRIMARY | 9101 | 5541 |
| S2, S3, S4 | REPLICA | 9102, 9103, 9104 | 5542, 5543, 5544 |
| TEST | Chỉ dùng cho integration test | — | 5549 |

Biến môi trường của node (giá trị mặc định nằm trong `node-server/src/main/resources/application.yml`):

| Biến | Ý nghĩa | Mặc định |
|---|---|---|
| `NODE_ID` | Định danh node | `S1` |
| `NODE_ROLE` | `PRIMARY` hoặc `REPLICA` | `PRIMARY` |
| `GRPC_PORT` | Cổng gRPC, bind trên mọi interface | `9101` |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | Cơ sở dữ liệu của node | `localhost`, `5541`, `inventory` |
| `DB_USER`, `DB_PASSWORD` | Tài khoản cơ sở dữ liệu | `inventory` / `local_only_change_me`. Chỉ dùng cho môi trường cục bộ; đặt giá trị riêng khi triển khai |
| `DB_CONNECTION_TIMEOUT_MS` | Thời gian chờ lấy kết nối | `2000` |
| `PEER_S1` … `PEER_S4` | Địa chỉ `host:port` của các node | `localhost:9101` … `localhost:9104` |

Biến dành cho `local-pg.ps1`:
- `PG_BIN`: thư mục `bin` của PostgreSQL.
- `INVENTORY_PG_DATA`: thư mục dữ liệu, mặc định `%LOCALAPPDATA%\inventory-replication\pg`.
- `DB_PASSWORD`: mật khẩu tài khoản cơ sở dữ liệu.

## Script vận hành

| Lệnh | Chức năng |
|---|---|
| `doctor.ps1` | Kiểm tra môi trường (chỉ đọc) |
| `local-pg.ps1 init\|start\|stop\|status [-Node S1..S4\|TEST]` | Quản lý các PostgreSQL instance độc lập |
| `local-pg.ps1 reset [-Node …] [-Force]` | Xóa và tạo lại cơ sở dữ liệu `inventory`; Flyway migrate lại khi node khởi động |
| `local-pg.ps1 destroy [-Node …] [-Force]` | Xóa toàn bộ thư mục dữ liệu của instance |
| `run-local.ps1 [-Node Sx] [-NoWait]` | Chạy node ở nền và chờ đến khi `READY`; báo nguyên nhân và trả mã lỗi 1 nếu node không khởi động được |
| `stop-local.ps1 [-Node Sx]` | Dừng node. Trên Windows, việc dừng tương đương crash: shutdown hook không được thực thi |
| `run-node.ps1 -Id Sx` | Chạy một node ở foreground (dùng cho triển khai vật lý); `Ctrl+C` để dừng có kiểm soát |
| `cluster-status.ps1 [host:port …]` | Gọi `GetStatus`; mặc định truy vấn 4 node cục bộ |
| `run-local.sh`, `stop-local.sh` | Chạy và dừng 4 node trên Linux, macOS và Git Bash. Phiên bản rút gọn: không chờ `READY`, không kiểm tra PID trước khi dừng, không quản lý PostgreSQL |

Log của node được ghi tại `logs/sN.log`.

**Lệnh build:**

| Mục đích | Lệnh |
|---|---|
| Build và chạy toàn bộ test | `.\mvnw.cmd -B -ntp clean install` |
| Build nhanh khi chỉ thay đổi `node-server` (yêu cầu đã chạy `install` một lần) | `.\mvnw.cmd -o -pl node-server package -DskipTests` |

**Docker (tùy chọn).** `docker compose -f deploy/docker-compose.yml up -d` khởi tạo PostgreSQL cho S1..S4 (5541..5544) và TEST (5549), thay cho `local-pg.ps1`. Khi dừng, dùng `down` mà không kèm `-v` để giữ lại dữ liệu.

## Triển khai trên nhiều máy

Mỗi máy chạy một node và một PostgreSQL. PostgreSQL chỉ lắng nghe trên `localhost`; chỉ cổng gRPC của node được mở ra mạng LAN.

> Ở phiên bản hiện tại, các node chưa gọi lẫn nhau (`cluster.peers` chưa được sử dụng). Quy trình dưới đây kiểm tra được việc khởi động, kết nối mạng và gRPC giữa các máy, **chưa** kiểm tra được đồng bộ dữ liệu.

1. **Trên mỗi máy:**
   - checkout cùng một commit, rồi chạy `.\mvnw.cmd -B -ntp clean install`;
   - cài PostgreSQL 16 và khởi tạo instance của node đó, ví dụ `.\scripts\local-pg.ps1 init -Node S2`.
2. **Mở cổng gRPC trên firewall.** Lệnh cần quyền Administrator; mạng phải ở chế độ *Private*. Sau khi kết thúc, gỡ rule bằng `Remove-NetFirewallRule`.

   ```powershell
   New-NetFirewallRule -DisplayName "inventory-node S2 gRPC" -Direction Inbound -Protocol TCP -LocalPort 9102 -Action Allow -Profile Private
   ```

3. **Khởi động** theo thứ tự S4, S3, S2, S1. Địa chỉ peer được truyền qua biến môi trường:

   ```powershell
   $env:PEER_S1 = '<ip-s1>:9101'
   $env:PEER_S2 = '<ip-s2>:9102'
   $env:PEER_S3 = '<ip-s3>:9103'
   $env:PEER_S4 = '<ip-s4>:9104'
   .\scripts\run-node.ps1 -Id S2
   ```

4. **Kiểm tra** từ máy client: `.\scripts\cluster-status.ps1 <ip-s1>:9101 <ip-s2>:9102 <ip-s3>:9103 <ip-s4>:9104`. Nếu một node trả lời khi gọi qua `localhost` trên chính máy đó nhưng không trả lời khi gọi từ máy khác, nguyên nhân là firewall.

**Lưu ý bảo mật:**
- gRPC hiện truyền plaintext và chưa xác thực; chỉ triển khai trong mạng LAN tin cậy.
- Không mở cổng PostgreSQL ra mạng.

## Xử lý sự cố

| Triệu chứng | Nguyên nhân và cách khắc phục |
|---|---|
| `Unable to clean up temporary proto file directory`, thiếu class `*Grpc`, hoặc `Proto path element is not a directory` | Một tiến trình khác (IDE hoặc một lệnh Maven khác) đang build vào `contracts/target`. Không chạy đồng thời hai lệnh Maven. Trong VS Code, chấp nhận cập nhật cấu hình project khi được hỏi. |
| IDE không nhận diện `NodeServiceGrpc` | Stub chỉ được sinh bởi Maven: chạy `mvnw clean install`, sau đó *Java: Clean Java Language Server Workspace*. |
| `clean` không xóa được file `.jar` | File jar đang được node sử dụng; chạy `stop-local.ps1` trước khi build. |
| Node dừng ngay, log có `Connection to localhost:554x refused` | PostgreSQL của node chưa chạy: kiểm tra bằng `local-pg.ps1 status`, rồi khởi động bằng `start`. |
| `FlywayValidateException`, `checksum mismatch` hoặc `Detected applied migration not resolved locally` | Cơ sở dữ liệu cục bộ chứa migration từ một nhánh khác. Chạy `stop-local.ps1`, rồi `local-pg.ps1 reset -Force`, rồi `run-local.ps1`. |
| Node dừng, log có `OutOfMemoryError: Java heap space` | Script khởi động node giới hạn heap ở `-Xmx256m` (quyết định 8 trong [thiet-ke.md](thiet-ke.md#5-quyết-định-kiến-trúc)). Tăng giá trị trong `run-local.ps1`, `run-node.ps1` và `run-local.sh`, đồng thời cập nhật quyết định. |
| `Cannot bind gRPC port 910x` | Cổng đã bị chiếm; dùng `doctor.ps1` để xác định tiến trình đang giữ cổng. |
| Log có `INTEGRATION TESTS SKIPPED` | Instance `TEST` chưa chạy: `local-pg.ps1 start -Node TEST`. |
| Cảnh báo `flyway-community-db-support … 401` hoặc `Mockito is currently self-attaching` | Không ảnh hưởng; có thể bỏ qua. |
