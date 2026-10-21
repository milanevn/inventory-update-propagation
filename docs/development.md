# Phát triển

Quy tắc Git, quyền sửa file, quy tắc code và kiểm thử. Cài đặt lần đầu: [setup.md](setup.md). Cách hệ thống hoạt động: [architecture.md](architecture.md).

## Nguồn chuẩn và cách thay đổi

- **Định nghĩa** (loại thông điệp, op, mã lỗi, khóa cấu hình, lược đồ CSDL) lấy theo code trong `common/`. Tài liệu chỉ giải thích, không chép lại.
- **Quy tắc** (Git, quyền sửa file, cách xử lý cập nhật) lấy theo thư mục `docs/` này.
- Muốn đổi quy tắc hoặc file trong `common/`: đề xuất trong nhóm chat, trưởng nhóm chốt, rồi sửa tài liệu và code **trong cùng một PR**.
- Giữ tương thích: chỉ thêm trường hoặc loại thông điệp mới. Muốn đổi nghĩa trường cũ phải báo cả nhóm trước.

## Git

### Nhánh

| Nhánh              | Dùng để                                            | Ai được gộp vào                                                                      |
| ------------------ | -------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `main`             | Bản chạy ổn định, dùng để demo                     | Chỉ trưởng nhóm, cuối mỗi giai đoạn, gộp từ `develop` qua PR                         |
| `develop`          | Ghép code của cả nhóm                              | Thành viên: qua PR, CI xanh và trưởng nhóm duyệt. Trưởng nhóm: push hoặc merge thẳng |
| `<tên>-module<số>` | Mỗi người code phần của mình, ví dụ `thuy-module1` | Chủ nhánh                                                                            |

### Luật PR

- Chỉ mở PR khi chức năng đã dùng được. Code dở thì commit và push lên nhánh riêng.
- Mỗi người tự mở PR từ nhánh của mình; trưởng nhóm không kéo nhánh về để PR hộ.
- CI đỏ thì tự sửa trên nhánh mình rồi push lại, không mở PR mới, không nhờ duyệt khi CI còn đỏ.
- Không force push lên nhánh của người khác hoặc nhánh chung.

### Commit

Mẫu: `<type>(<scope>): <mô tả>`

- **type:** `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `ci`.
- **scope:** `transport`, `db`, `primary`, `sync`, `heartbeat`, `lazy`, `recovery`, `client`, `common`, `ci`.
- **mô tả:** tiếng Việt có dấu, bắt đầu bằng động từ (thêm, sửa, gửi, kiểm tra…), viết thường, không chấm cuối, tối đa khoảng 72 ký tự. Tên trong code giữ nguyên tiếng Anh. Cần giải thích thêm thì viết ở phần thân, cũng bằng tiếng Việt.
- Mỗi commit một việc. Tiêu đề PR theo cùng mẫu. Thay đổi phá tương thích thì thêm `!` sau scope và phải qua mục [Nguồn chuẩn và cách thay đổi](#ngu%E1%BB%93n-chu%E1%BA%A9n-v%C3%A0-c%C3%A1ch-thay-%C4%91%E1%BB%95i).

Ví dụ:

```
feat(transport): gửi lại thông điệp với cùng msg_id khi hết timeout
fix(primary): từ chối STOCK_OUT khi số lượng vượt tồn
test(lazy): kiểm tra chỉ gửi lô khi đủ N cập nhật
```

### GitHub tự chặn những gì

| Cơ chế                           | Tác dụng                                                                                                                                                                                          |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Rulesets cho `main` và `develop` | Thành viên không push thẳng được; PR vào `develop` cần CI xanh và trưởng nhóm duyệt; chỉ trưởng nhóm cập nhật được `main`; cấm force push, cấm xóa nhánh. Trưởng nhóm được bỏ qua các quy tắc này |
| CI (`.github/workflows/ci.yml`)  | Mỗi PR hoặc push vào `develop`/`main`: kiểm tra cú pháp, test, check_env trên Windows sạch (Python 3.12, thêm một lượt 3.10)                                                                      |
| CODEOWNERS                       | Mọi PR tự động gán trưởng nhóm làm người duyệt                                                                                                                                                    |

### Git hằng ngày

Đầu mỗi buổi, lấy code mới của cả nhóm:

```
git switch develop
git pull
git switch <tên>-module<số>
git merge develop
```

Trong lúc code, commit nhỏ, mỗi commit một việc. Dùng `git add <tên file>`, không dùng `git add .`, để không đưa nhầm file đang sửa dở.

```
git add server/transport.py
git commit -m "feat(transport): gửi lại thông điệp với cùng msg_id khi hết timeout"
```

Khi chức năng đã dùng được:

1.  Chạy 3 lệnh ở mục [Kiểm thử](#ki%E1%BB%83m-th%E1%BB%AD), rồi `git push`. Lệnh này chỉ đưa code lên nhánh của bạn, **không tự tạo PR**.
2.  Mở PR trên GitHub: bấm **Compare & pull request** ở khung vàng (không thấy thì tab **Pull requests → New pull request**), chọn base **develop**.
3.  Mô tả 2–3 dòng: làm được gì, chạy thử thế nào, còn thiếu gì.
4.  Chờ CI xanh và trưởng nhóm Approve, rồi bấm **Merge pull request** (Create a merge commit).

CI đỏ hoặc có góp ý thì sửa trên nhánh mình rồi push lại, không mở PR mới.

## Quyền sửa file

### Ba mức quyền

- 🟢 **Của mình:** tự sửa thoải mái trên nhánh của mình.
- 🟡 **Dùng chung:** được sửa, nhưng báo trong nhóm chat trước khi mở PR, vì ảnh hưởng cả nhóm.
- Không có nhãn: file của người khác, **không được sửa**.

### Cây thư mục và người phụ trách

```
inventory-update-propagation/
├── .github/                    🟡  CODEOWNERS, CI
├── .gitattributes, .gitignore  🟡
├── README.md, requirements.txt 🟡
├── common/                     🟡  protocol, config, schema, interfaces, fakes
│   └── config.local.json           tự tạo trên máy mình, không lên Git
├── server/
│   ├── main.py                 🟡  điểm chạy máy chủ (--node S1..S4)
│   ├── transport.py            TV1 truyền thông tin cậy
│   ├── db.py, primary.py       TV2 SQLite, áp dụng cập nhật, bản chính (S1)
│   ├── replication_sync.py     TV3 lan truyền tức thời S1→S2
│   ├── heartbeat.py            TV3 phát hiện máy chết / sống lại
│   ├── replication_lazy.py     TV4 lan truyền trễ S1→S3, S2→S4
│   └── recovery.py             TV5 checkpoint, gửi bù, khôi phục
├── client/
│   ├── main.py                 🟡  cửa sổ chính Tkinter
│   ├── net.py                  TV1 gửi yêu cầu, tự gửi lại
│   └── views/
│       ├── inventory_view.py   TV2 nhập / xuất / xem tồn
│       ├── status_view.py      TV3 trạng thái 4 máy
│       ├── lag_view.py         TV4 chỉnh N, T, xem độ lệch
│       └── recovery_view.py    TV5 giả lập lỗi, khôi phục
├── scripts/
│   ├── run_all.py              TV1 bật nhanh 4 máy chủ
│   └── check_env.py            TV1 kiểm tra máy, kiểm tra mạng khi demo
├── tests/                      test_<module>.py: mỗi người file của mình; test_smoke.py 🟡
├── docs/                       🟡
└── data/                           tự sinh khi chạy, không lên Git
```

Mỗi thư mục chứa code có một file `__init__.py` rỗng để Python coi là gói; không xóa.

### Luôn cấm

- Sửa, đổi tên hoặc xóa file của người khác.
- Thêm, đổi tên, di chuyển file hoặc thư mục khác với cây ở trên khi chưa được trưởng nhóm chốt. Riêng file test `tests/test_<module>.py` của mình thì tự thêm.
- Mở file SQLite ở chỗ nào khác ngoài `server/db.py`; tự thêm bảng hay cột ngoài `common/schema.sql`.
- Thêm thư viện ngoài thư viện chuẩn Python khi chưa được nhóm duyệt.

### Thấy lỗi trong file của người khác

1.  Không tự sửa. Tạo issue nhãn `bug` trên GitHub, gắn tên chủ file, ghi cách tái hiện.
2.  Chủ file sửa trên nhánh của họ.
3.  Lỗi chặn việc của mình và chủ file đồng ý cho sửa hộ: sửa trên nhánh riêng, chủ file duyệt PR.

## Quy tắc code

**Tên trong code dùng tiếng Anh** (file, hàm, biến, bảng, cột, khóa cấu hình, trường thông điệp): viết thường nối bằng gạch dưới (`stock_out`). Giá trị `type`, `op`, mã lỗi viết HOA (`STOCK_OUT`). Tiếng Việt dùng cho chữ trên giao diện, thông báo lỗi cho người dùng, chú thích trong code, commit message và tiêu đề PR.

**Thời gian:** chuỗi ISO 8601 theo giờ máy (`2026-10-11T14:30:00`), tạo bằng `protocol.now_iso()`.

**Mở file văn bản luôn ghi** `**encoding="utf-8"**`, ví dụ `open(path, encoding="utf-8")`. Thiếu encoding thì Windows đọc theo cp1252 và làm hỏng tiếng Việt; CI sẽ báo lỗi.

**Import không được tự chạy gì** (mở socket, mở cửa sổ, tạo thread), kể cả `server/main.py`, `client/main.py`, `scripts/*.py`. Code khởi động đặt trong hàm và chỉ gọi dưới `if __name__ == "__main__":`

**Không ghi cứng** địa chỉ, cổng, N, T hay timeout trong code; luôn đọc qua `common/config.py`.

**Gắn module vào chương trình chính:** mỗi module server có hàm `start(ctx)`, `server/main.py` gọi lần lượt các hàm đó. Mỗi view có hàm `build_tab(notebook, ctx)`, `client/main.py` gọi lần lượt các hàm đó. Làm xong module thì mở PR thêm **đúng 1 dòng** gọi hàm của mình vào `main.py`.

**Tách logic khỏi mạng:** phần quyết định (kiểm tra tồn, đến lúc gửi chưa, máy chết chưa) viết thành hàm hoặc class không mở socket, nhận thời gian hiện tại qua tham số `now`, để test bằng `common/fakes.py` mà không phải `sleep`.

## Kiểm thử

### Ba lệnh CI chạy

Chạy trên máy mình ngay sau khi clone và trước mỗi lần mở PR. Máy mình đạt cả 3 thì CI cũng xanh.

| Lệnh                                                                                         | Kiểm tra gì                                                                                                                                                                                   |
| -------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `python -m compileall -q common server client scripts tests`                                 | **Cú pháp** mọi file `.py`. Không in gì là đạt                                                                                                                                                |
| `python -X warn_default_encoding -W error::EncodingWarning -m unittest discover -s tests -v` | **Toàn bộ test tự động** trong `tests/test_*.py`; có `open()` thiếu `encoding="utf-8"` thì báo lỗi                                                                                            |
| `python -m scripts.check_env`                                                                | **Môi trường máy:** phiên bản Python, đứng ở thư mục gốc repo, không nằm trong OneDrive/Dropbox, có Tkinter, có SQLite, ghi được thư mục data, file cấu hình hợp lệ, cổng 5001–5004 còn trống |

### Chạy một phần

| Lệnh                                                                              | Dùng khi           |
| --------------------------------------------------------------------------------- | ------------------ |
| `python -m unittest tests.test_schema -v`                                         | Chạy một file test |
| `python -m unittest tests.test_schema.NodeStateTest -v`                           | Chạy một nhóm test |
| `python -m unittest tests.test_schema.NodeStateTest.test_only_one_row_allowed -v` | Chạy đúng một test |

### Viết test

- Đặt trong `tests/test_<module>.py`; CI tự tìm mọi file `test_*.py`.
- Thay module chưa xong của người khác bằng bản giả trong `common/fakes.py`.
- Thêm file `.py` mới vào dự án thì thêm tên module vào danh sách `MODULES` trong `tests/test_smoke.py` (qua PR). Test này import thử từng module và kiểm tra import không tự chạy gì.

### CI trên GitHub

- CI chạy khi có PR vào `develop`/`main`, mỗi lần push thêm vào PR đó, và mỗi lần có code vào `develop`/`main`. Push lên nhánh riêng khi chưa mở PR thì CI không chạy. PR đang xung đột với `develop` thì CI cũng không chạy, nên merge `develop` vào nhánh mình trước.
- Mỗi PR có 2 dòng kiểm tra:
  - **CI / test** (Python 3.12, chạy cả 3 lệnh): bắt buộc xanh mới merge được.
  - **CI / test-py310** (Python 3.10, chạy test): không chặn merge, nhưng đỏ thì phải sửa.
- Đỏ thì bấm dấu ✗ xem log. Push thêm commit thì lần chạy cũ tự hủy ("Cancelled"). Chạy quá 10 phút thì CI tự dừng và báo đỏ.

```python
def main():
    ...  # mở socket, tạo thread, mở cửa sổ ở đây

if __name__ == "__main__":
    main()
```
