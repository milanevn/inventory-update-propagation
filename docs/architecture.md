# Kiến trúc

Hệ thống hoạt động thế nào ở mức kỹ thuật: thông điệp, quy tắc áp dụng cập nhật, lan truyền và CSDL. Danh sách loại thông điệp, op và mã lỗi xem trực tiếp trong `common/protocol.py`; khóa cấu hình xem `common/config.json` và [setup.md](setup.md#cấu-hình).

## Tổng thể

![Sơ đồ tổng thể](images/so_do_tong_the.png)

- S1–S4 là 4 tiến trình chạy cùng mã nguồn (`python -m server.main --node S1`), mỗi tiến trình một file SQLite riêng, chỉ trao đổi với nhau qua TCP.
- **Chỉ S1 nhận ghi.** Mỗi lần ghi, S1 cấp một số thứ tự `seq` rồi lan truyền: S1 → S2 tức thời, S1 → S3 mỗi T giây, S2 → S4 khi đủ N cập nhật.
- Mọi máy áp dụng cập nhật **đúng thứ tự seq, mỗi seq đúng một lần**, nên dữ liệu cuối cùng giống nhau. Muốn biết một máy thiếu gì, so `last_seq` của nó với máy nguồn.

## Thông điệp

- Mọi thông điệp tạo bằng `make_message` và gửi/nhận qua `encode`/`decode` trong `common/protocol.py`. Các hàm này lo định dạng (một dòng JSON, UTF-8, kết thúc bằng `\n`), đủ 7 trường bắt buộc và giới hạn 1 MB; lô lớn hơn thì chia nhỏ.
- Mỗi thông điệp gửi đi nhận lại **đúng một trả lời** trên cùng kết nối (`REPLY`, `ACK`, `NACK`…). Hết timeout mà chưa có thì gửi lại với **cùng `msg_id`**.
- `msg_id` đánh dấu từng thông điệp (gửi lại giữ nguyên). `seq` đánh dấu từng cập nhật dữ liệu; cả 4 máy dùng chung một dãy seq do S1 cấp.
- Chỉ S1 nhận thao tác ghi (`ADD_PRODUCT`, `STOCK_IN`, `STOCK_OUT`); máy khác trả `NACK` mã `NOT_PRIMARY`. `GET_STOCK` gửi máy nào cũng được, trả lời kèm `last_seq` của máy đó.
- S1 điền sẵn mọi giá trị trong bản ghi cập nhật (số phiếu, thời gian, số lượng…) để máy sau áp dụng ra đúng cùng kết quả. Số phiếu: `PN-`/`PX-` + 6 chữ số của seq (hàm `voucher_no`).
- Máy chủ trả lỗi bằng mã; client đổi mã thành câu tiếng Việt bằng `ERROR_MESSAGES_VI`.
- Kết nối TCP: client mở kết nối mới cho mỗi yêu cầu; giữa các máy chủ giữ kết nối lâu, mất thì tự mở lại.

## Áp dụng cập nhật

Mọi máy áp dụng cập nhật bằng **một hàm dùng chung** trong `server/db.py`.

1. seq do S1 cấp, bắt đầu từ 1, tăng liên tục. Thao tác đọc và thao tác bị từ chối không sinh seq.
2. Mỗi máy lưu `last_seq`. Nhận bản ghi có seq = s:
   - s ≤ `last_seq`: bản trùng. Bỏ qua, vẫn trả `ACK` kèm `last_seq`.
   - s = `last_seq` + 1: áp dụng, tăng `last_seq`.
   - s > `last_seq` + 1: thiếu ở giữa. Không áp dụng, trả `NACK` mã `SEQ_GAP` kèm `last_seq`; bên gửi gửi lại từ `last_seq` + 1.
3. Áp dụng một bản ghi là **một giao tác SQLite**: thêm phiếu (nếu có), cập nhật `inventory`, ghi `update_log`, cập nhật `last_seq`, và trên S1 ghi thêm `processed_requests`. Xong cả hoặc không gì cả.
4. Trong `UPDATE_BATCH`, áp dụng lần lượt theo quy tắc 2; dừng ở đâu thì `ACK`/`NACK` trả `last_seq` tại đó.
5. Máy sau không kiểm tra nghiệp vụ lại; S1 đã kiểm tra và điền sẵn mọi giá trị.
6. Client gửi lại cùng `msg_id`: S1 tra `processed_requests` và trả lại đúng `REPLY` cũ, không nhập/xuất thêm lần nữa.
7. Hàng đợi chờ gửi không phải bảng riêng: với mỗi máy đích, bên gửi lưu `last_acked_seq` trong `replication_progress`; cần gửi là các dòng `update_log` có seq lớn hơn số đó. Nhật ký nằm trên đĩa nên hàng đợi tự bền khi máy sập.
8. S1 → S2: S1 áp dụng xong ở chính nó rồi gửi `UPDATE` cho S2 và chờ `ACK`. S2 không trả lời thì S1 vẫn trả `REPLY` thành công cho client với `replicated_to_s2: false`; cập nhật nằm lại trong nhật ký và được gửi bù khi S2 sống lại.

## Lan truyền

| Đường   | Chế độ (`mode`) | Khi nào gửi                                                           |
| ------- | --------------- | --------------------------------------------------------------------- |
| S1 → S2 | `immediate`     | Ngay sau mỗi cập nhật, chờ `ACK` (quy tắc 8)                          |
| S1 → S3 | `periodic`      | Mỗi T giây gửi một lô các cập nhật mới; không có gì mới thì không gửi |
| S2 → S4 | `batch`         | Chỉ khi đủ N cập nhật mới kể từ lần gửi trước                         |

- Mỗi máy chủ chọn vai trò bằng `--node`; máy nào đẩy cho máy nào lấy từ `replication` trong cấu hình.
- Gửi bù khi một máy khởi động lại (`SYNC_REQUEST`/`SYNC_RESPONSE`) không phụ thuộc N, T. Máy nguồn đã xóa phần nhật ký cần gửi thì trả `LOG_TRUNCATED`; máy đích xin chép toàn bộ (`SNAPSHOT_REQUEST`/`SNAPSHOT_RESPONSE`).
- N, T đổi khi đang chạy (`CONFIG_SET`) được lưu vào `node_state`, khởi động lại không mất. Chưa đổi lần nào thì để trống và dùng giá trị trong `config.json`.

## Phục hồi

- **Máy sập rồi bật lại:** xem `last_seq` của mình, xin máy nguồn gửi bù từ `last_seq` + 1. Máy nguồn không còn đủ nhật ký thì chép lại toàn bộ, rồi gửi bù phần còn lại.
- **Dữ liệu bị hỏng** (checksum lệch với máy nguồn, hoặc file lỗi): nạp lại checkpoint gần nhất, phát lại nhật ký từ sau checkpoint đó. Không được thì xin gửi bù; vẫn không được thì chép toàn bộ từ máy nguồn. Cuối cùng so lại checksum với máy nguồn.
- S1 không có máy nguồn; cách khôi phục S1 do TV5 đề xuất.

## CSDL

Cả 4 máy dùng chung lược đồ `common/schema.sql`, mỗi máy một file `data/<node>/inventory.db`. Sơ đồ ERD: [drawdb](https://www.drawdb.app/share/0ijEHyPZlK9UwBhcqcikA0Y2). Sơ đồ và file schema khác nhau thì **theo file schema**.

### Vì sao dùng SQLite

- Có sẵn trong Python, không cài thêm gì, không cần chạy server CSDL.
- Mỗi CSDL là một file: 4 máy độc lập thật sự; giả lập "dữ liệu hỏng" chỉ cần sửa hoặc xóa file; checkpoint chỉ là chép file.
- Có giao tác (ACID): ghi phiếu, cập nhật tồn và ghi nhật ký xong cả hoặc không gì cả.
- **Lý do chính:** SQLite không có sẵn tính năng nhân bản như MySQL hay PostgreSQL, nên nhóm tự cài đặt việc lan truyền, đó là trọng tâm của môn học.
- Nhược điểm: chỉ một người ghi tại một thời điểm. Với đề này thì không sao, vì mọi thao tác ghi đều dồn về S1 và được xử lý lần lượt.

### Các bảng

| Nhóm      | Bảng                     | Lưu gì                                                                                                                          |
| --------- | ------------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| Nghiệp vụ | `products`               | Danh mục hàng hóa                                                                                                               |
|           | `stock_in` / `stock_out` | Phiếu nhập / phiếu xuất. Mỗi phiếu một mặt hàng; số phiếu `PN-`/`PX-` + 6 chữ số của seq; đơn giá tính bằng VNĐ, không bắt buộc |
|           | `inventory`              | Số tồn hiện tại của từng mặt hàng (không bao giờ âm)                                                                            |
| Kỹ thuật  | `update_log`             | Nhật ký mọi cập nhật theo thứ tự seq, dùng để lan truyền và phục hồi                                                            |
|           | `node_state`             | Một dòng duy nhất: `last_seq`, `checkpoint_seq`, N và T nếu đã đổi khi đang chạy                                                |
|           | `replication_progress`   | Mỗi máy đích đã nhận đến seq nào (cũng chính là hàng đợi chờ gửi)                                                               |
|           | `processed_requests`     | Các yêu cầu của client đã xử lý, để client gửi lại không bị làm hai lần                                                         |

Bảng nghiệp vụ nối với nhau bằng khóa ngoại qua `product_code`. Bảng kỹ thuật liên kết với dữ liệu qua **số seq**, không dùng khóa ngoại, để còn xóa bớt nhật ký cũ sau checkpoint.

### Quy ước

- Mỗi kết nối SQLite phải bật `PRAGMA foreign_keys = ON` (trong `server/db.py`); SQLite mặc định không kiểm tra khóa ngoại.
- Mỗi phiếu một mặt hàng.
- `ADD_PRODUCT` tạo luôn dòng `inventory` với tồn = 0, trong cùng giao tác.
- Không có chức năng tồn đầu kỳ: hàng có sẵn thì nhập bằng phiếu nhập, ghi chú "Tồn đầu kỳ".
- `CHECK (quantity >= 0)` ở `inventory` là lớp bảo vệ cuối; kiểm tra chính nằm ở S1 trước khi cấp seq.
- Cột `seq` trên bảng nghiệp vụ cho biết dòng đó sinh ra hoặc thay đổi gần nhất từ cập nhật nào.
- Sau checkpoint, được xóa dòng `update_log` cũ hơn `checkpoint_seq`, nhưng giữ các dòng máy đích chưa ACK (so với `replication_progress.last_acked_seq`).
- Checksum so dữ liệu giữa các máy không đưa `applied_at` vào, vì cột này mỗi máy một khác.

## Thuật ngữ

Chỉ ghi các tên chưa tự giải thích. Op và mã lỗi xem `common/protocol.py`.

| Tên trong code                           | Nghĩa                                                 |
| ---------------------------------------- | ----------------------------------------------------- |
| `UPDATE` / `UPDATE_BATCH`                | Gửi một cập nhật (tức thời) / một lô cập nhật (trễ)   |
| `ACK` / `NACK`                           | Xác nhận đã nhận / báo không xử lý được               |
| `SYNC_REQUEST` / `SYNC_RESPONSE`         | Xin gửi bù / gửi bù các cập nhật còn thiếu            |
| `SNAPSHOT_REQUEST` / `SNAPSHOT_RESPONSE` | Xin / gửi bản chụp toàn bộ dữ liệu                    |
| `CONFIG_SET`                             | Đổi N, T khi đang chạy                                |
| `ADMIN`                                  | Lệnh quản trị để demo (giả lập lỗi, khôi phục)        |
| `last_seq`                               | seq cuối cùng máy này đã áp dụng                      |
| `last_acked_seq`                         | seq cuối cùng máy đích đã xác nhận                    |
| `from_seq`                               | Xin gửi bù kể từ seq này                              |
| `checkpoint_seq`                         | seq tại điểm kiểm tra gần nhất                        |
| `replicated_to_s2`                       | Cập nhật đã sang S2 hay chưa                          |
| `applied_at`                             | Thời điểm máy này áp dụng cập nhật (mỗi máy một khác) |
| `immediate` / `periodic` / `batch`       | Lan truyền tức thời / mỗi T giây / theo lô N cập nhật |
| checksum / checkpoint                    | Mã kiểm tra để so dữ liệu / bản chụp dữ liệu định kỳ  |
