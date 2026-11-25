# Hướng dẫn đóng góp

## 1. Chuẩn bị

1. Làm theo [Bắt đầu nhanh](README.md#bắt-đầu-nhanh) đến khi cả 4 node `READY` và integration test không bị bỏ qua.
2. Đặt `git config user.name` và `user.email` khớp tài khoản GitHub (nên dùng email noreply của GitHub). Trên Windows, đặt thêm `git config core.fileMode false`.
3. Đọc [docs/thiet-ke.md](docs/thiet-ke.md) trước khi sửa phần replication, phục hồi hoặc giao thức.
4. Nếu dùng AI coding agent: quy tắc cho agent nằm trong [AGENTS.md](AGENTS.md) (Claude Code đọc qua `CLAUDE.md`). Agent không thay thế việc tự review trước khi mở Pull Request.

## 2. Nhánh và Pull Request

| Nhánh | Vai trò |
|---|---|
| `main` | Nhánh duy nhất lâu dài: luôn build, test và chạy được cụm 4 node. Chỉ nhận Pull Request từ các nhánh `feature/*`. |
| `feature/<module>-<mo-ta-ngan>` | Mỗi việc một nhánh, tạo từ `main`, xóa sau khi merge. Ví dụ: `feature/contracts-v2`, `feature/replication-apply-events`, `feature/policy-delay-s3`. |

`<module>` là tên module hoặc package trong [thiết kế](docs/thiet-ke.md#3-kiến-trúc) (`inventory`, `replication`, `policy`, `recovery`, `contracts`, `cli`...), hoặc `docs`, `scripts`. Sửa lỗi cũng dùng nhánh `feature/*`. Không tạo loại nhánh khác.

```powershell
git switch main; git pull
git switch -c feature/replication-apply-events
```

- Không push trực tiếp lên `main`; mọi thay đổi đi qua Pull Request.
- Trước khi mở Pull Request: cập nhật `main` mới nhất vào nhánh của mình (`git pull origin main`) và chạy `mvnw verify`.
- Pull Request nhỏ, một mục đích, ghi rõ mã công việc và kịch bản nghiệm thu liên quan. Nhánh sống ngắn; việc lớn thì chia thành nhiều Pull Request.
- Merge khi có ít nhất một approve và CI xanh. Thay đổi giao thức, schema hoặc quyền ghi của primary cần maintainer duyệt.
- Merge bằng *Squash and merge*: mỗi Pull Request thành một commit trên `main`, tiêu đề Pull Request là commit message.
- Khi các kịch bản nghiệm thu của một mốc đều đạt, maintainer gắn tag `v0.<n>.0` trên `main`; tag là mốc có thể quay lại.
- Commit message và tiêu đề Pull Request theo [Conventional Commits](https://www.conventionalcommits.org/): giữ type và scope theo quy ước tiếng Anh, scope là tên module; phần mô tả sau dấu `:` viết bằng tiếng Việt. Ví dụ: `feat(replication): áp dụng lô sự kiện liên tục`, `fix(inventory): từ chối xuất kho vượt tồn`.

## 3. Quy ước code

- **Giao thức:** `contracts/src/main/proto/inventory.proto` là nguồn chuẩn duy nhất. Không sửa code sinh tự động. Thay đổi không tương thích ngược phải được thống nhất trước khi merge.
- **Schema:** không sửa migration đã phát hành; mọi thay đổi là migration mới `V<n>__<mo_ta>.sql`. Số `<n>` cấp theo thứ tự merge vào `main`: trước khi merge, nếu `main` đã có migration cùng số thì đổi số của mình sang số kế tiếp và chạy lại test. Kiểm migration chưa merge bằng integration test (instance TEST chạy Flyway `clean` mỗi lần); nếu đã chạy nó trên cụm cục bộ rồi mới sửa nội dung hoặc đổi số, node sẽ dừng với lỗi Flyway: xử lý theo [vận hành, mục 7](docs/van-hanh.md#7-lỗi-thường-gặp).
- **Package:** đặt code vào đúng package theo [thiết kế](docs/thiet-ke.md#3-kiến-trúc).
- **gRPC service:** chỉ cần là Spring bean kế thừa lớp `*ImplBase` sinh từ `.proto`; service được đăng ký tự động, không sửa `GrpcServerLifecycle`.
- **Cấu hình:** không hardcode địa chỉ, cổng hay vai trò node; mọi giá trị lấy từ cấu hình.
- **Logging:** dùng SLF4J, không dùng `System.out` trong server. Log replication có `seq` và `event_id`.
- **Phiên bản thư viện:** chỉ khai báo trong `pom.xml`; thư viện có trong BOM không ghi version riêng. Không dùng Lombok.
- **Quyết định kỹ thuật mới:** thêm vào [thiết kế, mục 7](docs/thiet-ke.md#7-quyết-định-kỹ-thuật) trong cùng Pull Request.

## 4. Kiểm thử

| Loại | Tên lớp | Chạy bởi | Cơ sở dữ liệu |
|---|---|---|---|
| Unit test | `*Test` | Surefire, trong `mvnw verify` | Không dùng |
| Integration test | `*IT`, kế thừa `AbstractNodeIT` | Failsafe, trong `mvnw verify` | Instance TEST (`localhost:5549`); CI dùng service `postgres:16` trên cùng cổng |

`AbstractNodeIT` khởi động node thật (S1, PRIMARY, gRPC trên cổng ngẫu nhiên) và chạy Flyway `clean` + `migrate` mỗi Spring context; chi tiết trong Javadoc của lớp này, ví dụ mẫu là `NodeStatusIT`.

- Mỗi test tự chuẩn bị và dọn dữ liệu của mình; không phụ thuộc thứ tự chạy.
- Integration test không được kết nối tới cơ sở dữ liệu của S1..S4.
- Chính sách trễ được test bằng đồng hồ mô phỏng, không dùng `sleep`.
- Không mô phỏng split-brain bằng cách chỉ đổi cờ vai trò trong bộ nhớ; dừng node thật bằng `stop-local.ps1`, dừng cơ sở dữ liệu bằng `local-pg.ps1 stop`.
- Integration test kiểm logic của một node. Muốn node đóng vai replica, cập nhật `node_state.role` trong test (vai trò lấy từ `node_state`) và gọi RPC qua stub gRPC như `NodeStatusIT`.
- Kịch bản cần nhiều node (lan truyền, mất mạng, promote, primary cũ quay lại, phục hồi) nghiệm thu bằng E2E trên cụm cục bộ: `run-local.ps1`, CLI và script chèn lỗi; ghi lệnh đã chạy và kết quả làm bằng chứng.
- Test của một kịch bản nghiệm thu ghi mã kịch bản (`Txx`) trong Javadoc. Danh sách kịch bản nghiệm thu do maintainer quản lý ngoài repo; hỏi maintainer khi chưa rõ mã của việc mình làm.

| Chạy | Lệnh |
|---|---|
| Toàn bộ | `.\mvnw.cmd -B -ntp clean verify` |
| Một unit test | `.\mvnw.cmd -pl node-server -am test "-Dtest=NodePropertiesTest" "-Dsurefire.failIfNoSpecifiedTests=false"` |
| Một integration test | `.\mvnw.cmd -pl node-server -am verify "-Dit.test=NodeStatusIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false"` |
| Build nhanh `node-server`, bỏ qua test (đã `install` ít nhất một lần) | `.\mvnw.cmd -o -pl node-server package -DskipTests` |

Trong PowerShell, tham số `-D` có dấu chấm phải đặt trong ngoặc kép. Khi chạy cục bộ mà instance TEST chưa bật, integration test bị bỏ qua kèm cảnh báo `INTEGRATION TESTS SKIPPED`; trên CI, trường hợp này làm build thất bại.

## 5. Definition of Done

- [ ] `mvnw verify` thành công, integration test không bị bỏ qua.
- [ ] Có test cho trường hợp thành công, trùng lặp, lỗi mạng hoặc crash, và các bất biến dữ liệu liên quan.
- [ ] Không có secret trong mã nguồn hoặc cấu hình được commit.
- [ ] Tài liệu được cập nhật khi giao thức, schema hoặc cách vận hành thay đổi.
- [ ] Đã chạy thử trên cụm cục bộ và được review.
