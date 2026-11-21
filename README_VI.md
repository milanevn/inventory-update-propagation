# Đề 8 — Repo khởi động hệ thống cập nhật lan truyền

**Nhóm:** 5 người · **Hạn nộp:** 18/12/2026 · **Bản bàn giao ban đầu:** 21/11/2026

> **Quan trọng:** Đây là **repo skeleton chạy thử gRPC và PostgreSQL**, không phải đồ án đã hoàn thành. Logic nhập/xuất/tồn, replication và recovery được ghi thành hợp đồng và backlog để các thành viên lập trình sau kickoff.

## 1. Quyết định đã chốt

- **Java 21 + Spring Boot không dùng web + gRPC/Protobuf + PostgreSQL**.
- 4 server độc lập, chung mã nguồn, mỗi server có một PostgreSQL riêng.
- **Tự viết replication trong ứng dụng**: không bật PostgreSQL streaming/logical replication.
- S1→S2: gửi ngay sau commit, nhưng client không đợi ACK của S2.
- S1→S3: gửi không sớm hơn `n` giây sau commit (cho phép trễ hơn do lịch worker/mạng).
- S2→S4: chỉ gửi khi đủ `n` cập nhật mới đã áp dụng, không có cơ chế timeout tự xả batch.
- Cập nhật có `event_id`, `epoch`, `seq`; áp dụng theo thứ tự và chống ghi trùng.
- Hệ thống ghi tại S1 + operation_log + outbox **trong cùng transaction**.
- Khi S1 hỏng, không tự động cho replica chậm nhận ghi. Chuyển quyền primary là bài toán cần fencing và xác minh dữ liệu.

## 2. Repo có những gì?

| Phần | Trạng thái |
|---|---|
| Maven multi-module (4 modules) | Đã tạo cấu hình |
| `.proto` cho Inventory, Replication, Node | Đã soạn, cần Maven compile xác minh |
| Java gRPC server: `GetStatus` | Đã viết code mẫu |
| Java CLI gọi `GetStatus` | Đã viết code mẫu |
| PostgreSQL schema/migration với Flyway | Đã soạn SQL |
| Docker Compose cho 4 PostgreSQL độc lập | Đã soạn cấu hình |
| Tài liệu 6 giai đoạn + ADR + kế hoạch kickoff | Đã soạn |
| CI `mvn verify` | Đã soạn workflow |
| CRUD nhập/xuất/tồn | **Chưa viết** |
| Engine, 3 chính sách replication | **Chưa viết** |
| Snapshot/recovery, an toàn failover | **Chưa viết** |
| JavaFX và E2E fault testing | **Chưa viết** |

**Kiểm chứng đã thực hiện khi tạo repo:** XML POM, YAML, tồn tại file cần thiết, biên dịch độc lập `common-domain` bằng Java 21. **Chưa chạy toàn dự án bằng Maven, gRPC, Docker/PostgreSQL** vì môi trường tạo repo không có Maven/Docker.

## 3. Chạy thử trên Windows 11

1. Cài **JDK 21**, **Maven 3.9+**, **Docker Desktop** (Docker là tùy chọn; có thể dùng 4 PostgreSQL instance cài trực tiếp).
2. Mở PowerShell tại thư mục repo, chạy:

   ```powershell
   docker compose -f deploy/docker-compose.yml up -d
   mvn -B -ntp clean install
   .\scripts\run-local.ps1
   ```

3. 4 cửa sổ PowerShell cho S1..S4 xuất hiện. Mở cửa sổ khác và kiểm tra S1:

   ```powershell
   mvn -pl cli-client exec:java -Dexec.mainClass=edu.inventory.cli.NodeStatusCli -Dexec.args=localhost:9101
   ```

4. Kết quả kỳ vọng: `node=S1 role=PRIMARY epoch=1 issued=0 applied=0 status=READY`. Thử S2 tại port 9102, S3 9103, S4 9104.

5. Nếu Maven/Docker lỗi, sửa lỗi môi trường trước khi chia module. Tại thời điểm bàn giao chưa có tính năng nhập/xuất hoặc nhân bản để demo.

## 4. Triển khai 4 máy vật lý

Mỗi máy chạy Java server + PostgreSQL cục bộ. Ví dụ trên máy S2: `NODE_ID=S2`, `NODE_ROLE=REPLICA`, `GRPC_PORT=9100`, `DB_HOST=127.0.0.1`, `DB_PORT=5432`. Kiểm tra RPC từ client bằng `<IP-LAN-S2>:9100`. Mã nguồn không được phụ thuộc `localhost` khi gọi peer; các peer addresses trong `application.yml` là mẫu cho TV2 cấu hình. Chưa triển khai kết nối replica thực sự trong skeleton.

**Bảo mật:** RPC mẫu dùng plaintext, không xác thực; chỉ dùng trong môi trường demo LAN tin cậy. Khi mở mạng rộng cần TLS/mTLS và xác thực các lệnh nội bộ/quản trị.

## 5. Công việc của từng thành viên

- **TV1 (Tech Lead):** nghiệp vụ, transaction, cấp `seq`, operation_log + outbox, tích hợp.
- **TV2:** gRPC transport, gửi/nhận sự kiện, ACK/retry, kiểm tra thứ tự, phát hiện trùng và thiếu.
- **TV3:** 3 policy `immediate/delay/batch`, cấu hình n, scheduling bền vững.
- **TV4:** snapshot, log replay, phát hiện lỗi, quarantine, recovery và giới hạn chuyển quyền primary.
- **TV5:** CLI/JavaFX tối giản, monitor, test lỗi mạng/JVM và triển khai 4 máy.

Mỗi người phải có code + test + reviewer, không giao một bạn chỉ làm giao diện. Chi tiết trong [docs/06-handover.md](docs/06-handover.md).

## 6. Những tài liệu phải đọc theo thứ tự

1. [01-requirements](docs/01-requirements.md) — yêu cầu và giả định còn mở.
2. [02-architecture](docs/02-architecture.md) — topology, transaction, SQL, triển khai.
3. [03-protocol](docs/03-protocol.md) — `.proto`, ACK, retry, 3 chính sách.
4. [04-recovery](docs/04-recovery.md) — lỗi, dữ liệu hỏng, phục hồi và giới hạn an toàn.
5. [05-test-plan](docs/05-test-plan.md) — 20 kịch bản kiểm thử.
6. [06-handover](docs/06-handover.md) — phân công, PR, backlog, milestone.
7. [07-kickoff-agenda](docs/07-kickoff-agenda.md) — kịch bản kickoff.

Tất cả tài liệu kỹ thuật chi tiết hiện viết bằng tiếng Anh để tên giao thức và contracts thống nhất. Không thay thế tài liệu môn học: nguồn bài giảng được liệt kê tại [docs/REFERENCES.md](docs/REFERENCES.md).

## 7. Đưa repo lên GitHub của nhóm

Bản ZIP đã có local `.git`, commit ban đầu cùng hai nhánh `main` và `develop` nhưng **chưa có remote**.

1. Tạo repository trống trên tài khoản/tổ chức GitHub của team (nên Private).
2. Trong thư mục repo chạy:

   ```bash
   git remote add origin <URL-REPO-CUA-NHOM>
   git push -u origin main
   git push -u origin develop
   ```

3. Bật branch protection, cấm push trực tiếp, yêu cầu PR có review và `Java CI` thành công.
4. Bàn giao quyền truy cập, branch của từng người và checklist kickoff.

**Không được ghi nhận dự án đã chạy thành công trước khi có kết quả `mvn verify` và smoke test thực tế.**
