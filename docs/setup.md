# Cài đặt và chạy

Hướng dẫn lấy dự án về máy lần đầu, cấu hình và chạy hệ thống. Quy tắc Git và cách viết code: [development.md](development.md).

Mục ghi _(chưa có, TVx)_ phụ thuộc module chưa xong; người phụ trách bổ sung khi xong.

## Yêu cầu

| Thành phần   | Yêu cầu                                                             |
| ------------ | ------------------------------------------------------------------- |
| Python       | 3.10 trở lên. CI chạy test trên 3.12 và 3.10; nên dùng 3.12         |
| Hệ điều hành | Windows 10/11 (cả nhóm và CI đều dùng Windows)                      |
| Git          | 2.23 trở lên (cần lệnh `git switch`). Kiểm tra: `git --version`     |
| Quyền repo   | Write trên repo GitHub (trưởng nhóm mời)                            |

- Khi cài Python, giữ mục **tcl/tk and IDLE** (mặc định đã chọn) để có Tkinter.
- Gõ `python` mà Windows mở Microsoft Store nghĩa là Python chưa nằm trong PATH. Dùng `py` thay cho `python`, hoặc cài lại Python và tick **Add python.exe to PATH**.
- Chỉ dùng thư viện chuẩn của Python: không `pip install`, không venv. `requirements.txt` để trống là cố ý.

## Lấy mã nguồn

Làm một lần:

```powershell
git clone https://github.com/milanevn/inventory-update-propagation.git
cd inventory-update-propagation
git switch develop
git switch -c <tên>-module<số>
git push -u origin <tên>-module<số>
```

Clone vào thư mục thường (ví dụ `C:\code\` hoặc Documents), **không** clone vào thư mục OneDrive/Dropbox đang đồng bộ, vì trình đồng bộ khóa file SQLite.

Kiểm tra máy ngay sau khi clone:

```powershell
python -m scripts.check_env
```

Đúng khi mọi dòng là `[OK  ]` và dòng cuối là `Tất cả OK, máy đã sẵn sàng.`. Dòng `[LOI ]` thì làm theo gợi ý ở dòng `->` ngay bên dưới; dòng `[CHU Y]` là cảnh báo. Lần đầu chạy, nếu Windows hỏi về tường lửa, tick **Private networks** rồi bấm **Allow**.

**Luôn chạy từ thư mục gốc repo và dùng `python -m`.** Các module import nhau theo tên gói, nên chạy thẳng file sẽ lỗi. Nút Run của editor cũng chạy thẳng file, nên dùng terminal.

```powershell
python scripts/run_all.py    # ModuleNotFoundError: No module named 'common'
python -m scripts.run_all    # đúng
```

## Cấu hình

Mọi máy chủ và client đọc cấu hình qua `common/config.py`: mặc định `common/config.json`; máy nào có `common/config.local.json` thì dùng file đó thay thế. Ý nghĩa từng khóa:

| Khóa                                                 | Ý nghĩa                                                                |
| ---------------------------------------------------- | ---------------------------------------------------------------------- |
| `nodes`                                              | host và port của S1–S4 (mặc định 127.0.0.1, cổng 5001–5004)            |
| `batch_n`                                            | N: số cập nhật gom thành một lô S2 → S4 (mặc định 5)                   |
| `period_t_seconds`                                   | T: chu kỳ gửi S1 → S3, tính bằng giây (mặc định 10)                    |
| `ack_timeout_seconds`, `max_retries`                 | Chờ xác nhận bao lâu, gửi lại tối đa mấy lần                           |
| `heartbeat_interval_seconds`, `heartbeat_miss_limit` | Nhịp tín hiệu sống; mất bao nhiêu nhịp liên tiếp thì coi là máy chết   |

Không sửa `primary`, `replication`, `data_dir`. Đổi giá trị mặc định cho cả nhóm thì sửa `config.json` qua PR.

Dùng trong code:

```python
from common import config
cfg = config.load()                    # đọc config.local.json nếu có, không thì config.json; sai cấu trúc báo ValueError
host, port = config.address(cfg, "S2") # ("127.0.0.1", 5002)
config.upstream(cfg, "S4")             # "S2": máy nguồn của S4
config.downstreams(cfg, "S1")          # [("S2", "immediate"), ("S3", "periodic")]
config.data_path(cfg, "S1")            # <repo>\data\S1\inventory.db
```

### Cấu hình riêng trên máy mình

Muốn thử giá trị khác (đổi IP, N, T, timeout) thì **không sửa `config.json`**, mà tạo bản riêng:

```powershell
Copy-Item common\config.json common\config.local.json
```

Mở `config.local.json`, sửa đúng giá trị cần thử, giữ nguyên các khóa khác (thiếu khóa nào sẽ báo ValueError). File này nằm trong `.gitignore` nên không bị commit. Đưa nó lên Git thì người khác pull về sẽ chạy theo cấu hình của bạn mà không biết.

```powershell
python -c "from common import config; print(config.resolve_path())"   # đang đọc file nào
Remove-Item common\config.local.json                                    # quay về mặc định
```

## Dữ liệu

Mỗi máy chủ có một file SQLite riêng: `data\S1\inventory.db` … `data\S4\inventory.db`. Thư mục `data\` không lên Git.

- **Không cần tạo tay.** Máy chủ khởi động lần đầu tự tạo `data\<node>\inventory.db` từ `common/schema.sql`.
- **Xem dữ liệu:** mở file bằng DB Browser for SQLite **khi máy chủ đã tắt**. Mở lúc máy chủ đang chạy sẽ gây lỗi `database is locked`.
- **Làm lại từ đầu:** tắt **cả 4** máy chủ rồi xóa toàn bộ thư mục data:

  ```powershell
  Remove-Item -Recurse -Force data
  ```

  Không xóa riêng một máy: mỗi máy nhớ `last_seq` và bỏ qua cập nhật có seq ≤ số đó. Xóa riêng S1 thì S1 cấp lại seq từ 1, còn S2–S4 coi mọi cập nhật mới là bản trùng.
- **Thử "một máy sập rồi bật lại":** không xóa dữ liệu, chỉ tắt rồi bật lại máy đó.

## Chạy hệ thống

> Hiện `server/main.py`, `client/main.py`, `scripts/run_all.py` mới là khung. Các lệnh dưới đây chạy được khi module hoàn thành.

Hệ thống gồm 5 tiến trình: 4 máy chủ và 1 client, tất cả chạy từ thư mục gốc repo.

```powershell
python -m scripts.run_all            # bật nhanh cả 4 máy chủ
python -m server.main --node S1      # hoặc bật từng máy, mỗi máy một terminal (S1..S4)
python -m client.main
```

`--node` chọn vai trò; máy nào đẩy cho máy nào lấy từ `replication` trong cấu hình.

Thứ tự bật máy, thông tin in ra khi khởi động, cách tắt để giả lập sự cố: _(chưa có, TV1)_.

### Demo trên nhiều laptop

1. Các laptop vào cùng một mạng. Wi-Fi trường thường chặn các máy kết nối với nhau, nên dùng hotspot điện thoại.
2. Mỗi laptop chạy `ipconfig`, lấy dòng IPv4 Address của mạng đang dùng.
3. Một người tạo `config.local.json` (mục [Cấu hình riêng trên máy mình](#cấu-hình-riêng-trên-máy-mình)), sửa `host` của S1–S4 thành IP thật của từng laptop, rồi gửi file cho cả nhóm. Mọi laptop dùng cùng nội dung này.
4. Mỗi laptop chạy đúng `--node` của mình.
5. Khi các máy đã bật, trên từng laptop chạy `python -m scripts.check_env --network`. Dòng nào `[LOI ]` thì làm theo gợi ý (máy kia chưa bật, khác mạng, tường lửa chặn).

### Kiểm tra thủ công

Khi đã ghép các module lan truyền: bật cả hệ thống, thêm một mặt hàng và nhập kho trên S1, rồi xem tồn ở từng máy.

| Máy      | Kết quả đúng                                  |
| -------- | --------------------------------------------- |
| S2       | Thấy cập nhật ngay                            |
| S3       | Thấy cập nhật sau tối đa T giây               |
| S4       | Chưa thấy cho đến khi S2 có đủ N cập nhật mới |
| Cả 4 máy | Sau khi đồng bộ: cùng `last_seq`, cùng số tồn |

## Lỗi thường gặp

| Lỗi | Cách xử lý |
| --- | --- |
| `ModuleNotFoundError: No module named 'common'` | Chạy từ thư mục gốc repo, dùng `python -m …` |
| `ModuleNotFoundError: No module named 'scripts.run_all'` (dù đang ở thư mục gốc) | Kiểm tra `scripts\__init__.py` còn không; lỡ xóa thì `git restore scripts/__init__.py` |
| `ModuleNotFoundError: No module named 'tkinter'` | Chạy lại bộ cài Python → **Modify** → tick **tcl/tk and IDLE** |
| Gõ `python` thì mở Microsoft Store | Dùng `py`, hoặc cài lại Python và tick **Add python.exe to PATH** |
| `OSError: [WinError 10048]`, hoặc check_env báo "đang có chương trình khác nghe" | Cổng đang bị tiến trình cũ chiếm. Tắt các terminal máy chủ cũ; không biết tiến trình nào thì `netstat -ano \| findstr :5001`, lấy số ở cột cuối (PID), rồi `taskkill /PID <số> /F` |
| `OSError: [WinError 10013]` | Windows giữ sẵn dải cổng cho Hyper-V/WSL/Docker. Xem: `netsh interface ipv4 show excludedportrange protocol=tcp`; đổi port trong `config.local.json` |
| `OSError: [WinError 10049]` | host trong cấu hình không phải IP của máy này (thường do đổi Wi-Fi). Xem `ipconfig`, sửa `config.local.json` |
| `ValueError: config.json thiếu khóa…` / không phải JSON hợp lệ / `'port' phải là…` | Đọc thông báo để biết sai khóa hay máy nào. Có `config.local.json` thì sửa hoặc xóa file đó; sai ở `config.json` thì `git restore common/config.json` |
| `git status` vẫn hiện `common/config.local.json` | File đã lỡ commit từ trước: `git rm --cached common/config.local.json` rồi commit |
| `sqlite3.OperationalError: database is locked` | Còn máy chủ cũ đang mở file, hoặc repo nằm trong OneDrive/Dropbox. Tắt máy chủ cũ; nếu cần, clone lại vào thư mục thường |
| `EncodingWarning: 'encoding' argument not specified` | Có lệnh `open()` thiếu encoding; log chỉ ra file và dòng. Thêm `encoding="utf-8"` |
| Test treo, hoặc CI báo `exceeded the maximum execution time of 10 minutes` | Có module tự mở socket, cửa sổ hoặc thread khi import. Đưa vào hàm `main()` (xem [development.md](development.md#quy-tắc-code)) |
| `warning: CRLF will be replaced by LF` khi `git add` | Bình thường, Git tự đổi sang LF khi commit. Muốn hết cảnh báo: VS Code → góc dưới phải bấm CRLF → chọn LF |
