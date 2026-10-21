# inventory-update-propagation

Hệ thống nhân bản dữ liệu kho hàng trên 4 máy chủ, có lan truyền cập nhật và phục hồi lỗi.

[![CI](https://github.com/milanevn/inventory-update-propagation/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/milanevn/inventory-update-propagation/actions/workflows/ci.yml) ![Python](https://img.shields.io/badge/python-3.10%2B-3776AB?logo=python&logoColor=white) ![SQLite](https://img.shields.io/badge/SQLite-003B57?logo=sqlite&logoColor=white) ![Platform](https://img.shields.io/badge/platform-Windows-0078D6?logo=windows&logoColor=white)

Đồ án môn **Các hệ thống phân tán** · Đề tài 8 · Giảng viên hướng dẫn: _[Họ tên giảng viên]_

> **Trạng thái:** đang phát triển. Các lệnh chạy ở mục [Cài đặt và chạy](#cài-đặt-và-chạy) hoạt động đầy đủ khi các module hoàn thành.

## Mục lục

- [Giới thiệu](#giới-thiệu)
- [Tính năng](#tính-năng)
- [Kiến trúc](#kiến-trúc)
- [Công nghệ](#công-nghệ)
- [Cài đặt và chạy](#cài-đặt-và-chạy)
- [Kịch bản demo](#kịch-bản-demo)
- [Kiểm thử](#kiểm-thử)
- [Cấu trúc thư mục](#cấu-trúc-thư-mục)
- [Tài liệu](#tài-liệu)
- [Thành viên](#thành-viên)

---

## Giới thiệu

Ứng dụng quản lý **nhập – xuất – tồn** hàng hóa, trong đó dữ liệu được nhân bản trên 4 máy chủ. Máy chủ S1 là bản chính, nhận mọi thao tác ghi và lan truyền cập nhật sang các bản sao theo ba chế độ khác nhau. Hệ thống tự phục hồi khi máy chủ sập, khi thông điệp bị mất hoặc khi dữ liệu bị hỏng.

Toàn bộ cơ chế nhân bản được tự cài đặt trên nền TCP socket và SQLite, không dùng tính năng replication có sẵn của hệ quản trị CSDL.

## Tính năng

**Nghiệp vụ kho**

- Thêm hàng hóa, nhập kho, xuất kho, xem tồn
- Không cho phép tồn âm, kể cả khi nhiều client xuất cùng lúc

**Lan truyền cập nhật**

| Đường   | Chế độ   | Điều kiện gửi                       |
| ------- | -------- | ----------------------------------- |
| S1 → S2 | Tức thời | Ngay sau mỗi cập nhật, chờ xác nhận |
| S1 → S3 | Định kỳ  | Mỗi **T** giây                      |
| S2 → S4 | Theo lô  | Khi đủ **N** cập nhật mới           |

N và T thay đổi được khi hệ thống đang chạy.

**Chịu lỗi và phục hồi**

- Truyền thông tin cậy: ACK, timeout, gửi lại, chống xử lý trùng
- Phát hiện máy chết / sống lại bằng heartbeat
- Máy khởi động lại tự xin gửi bù các cập nhật còn thiếu
- Khôi phục dữ liệu hỏng từ checkpoint và nhật ký cập nhật

## Kiến trúc

![Sơ đồ tổng thể](docs/images/so_do_tong_the.png)

- **Một chương trình máy chủ, bốn vai trò:** S1–S4 chạy cùng mã nguồn, phân biệt bằng tham số `--node`.
- **Ghi tập trung, đọc phân tán:** client chỉ ghi vào S1, đọc được từ bất kỳ máy nào.
- **Nhật ký có đánh số:** mỗi cập nhật mang một số thứ tự `seq` do S1 cấp; mọi máy áp dụng đúng thứ tự, mỗi `seq` đúng một lần, nên các bản sao luôn hội tụ về cùng dữ liệu.
- **Độc lập dữ liệu:** mỗi máy có một file SQLite riêng (`data/S1/inventory.db` … `data/S4/inventory.db`).

## Công nghệ

| Thành phần   | Công nghệ                         |
| ------------ | --------------------------------- |
| Ngôn ngữ     | Python 3.10+ (chỉ thư viện chuẩn) |
| Giao diện    | Tkinter                           |
| Truyền thông | TCP socket, JSON                  |
| Lưu trữ      | SQLite                            |
| CI           | GitHub Actions                    |

## Cài đặt và chạy

**Yêu cầu:** Windows 10/11, Python 3.10 trở lên (khuyên dùng 3.12, khi cài giữ mục _tcl/tk and IDLE_), Git. Không cần cài thêm thư viện nào.

```powershell
git clone https://github.com/milanevn/inventory-update-propagation.git
cd inventory-update-propagation
python -m scripts.check_env        # kiểm tra máy đã sẵn sàng
```

Mọi lệnh đều chạy **từ thư mục gốc repo** bằng `python -m`:

```powershell
python -m scripts.run_all          # bật cả 4 máy chủ S1–S4
python -m client.main              # mở ứng dụng client
```

Hoặc bật từng máy chủ, mỗi máy một cửa sổ terminal:

```powershell
python -m server.main --node S1    # tương tự với S2, S3, S4
```

Mặc định cả 4 máy chạy trên một máy tính (`127.0.0.1`, cổng 5001–5004, N = 5, T = 10 giây), cấu hình trong `common/config.json`. Để chạy trên nhiều laptop, tạo `common/config.local.json` từ file này và sửa `host` của từng máy. Hướng dẫn chi tiết và lỗi thường gặp: [docs/setup.md](docs/setup.md).

## Kịch bản demo

Thêm một mặt hàng và nhập kho trên S1, rồi xem tồn ở từng máy:

| Máy      | Kết quả đúng                                  |
| -------- | --------------------------------------------- |
| S2       | Thấy cập nhật ngay                            |
| S3       | Thấy cập nhật sau tối đa T giây               |
| S4       | Chỉ thấy khi S2 có đủ N cập nhật mới          |
| Cả 4 máy | Sau khi đồng bộ: cùng `last_seq`, cùng số tồn |

Các tình huống lỗi giả lập được trên giao diện:

| Tình huống                          | Hệ thống xử lý                                             |
| ----------------------------------- | ---------------------------------------------------------- |
| Nhiều client xuất cùng một mặt hàng | S1 xử lý lần lượt trong giao tác, không bao giờ tồn âm     |
| Mất hoặc trùng thông điệp           | Gửi lại theo timeout; cập nhật trùng bị bỏ qua             |
| Tắt một máy rồi bật lại             | Máy tự xin gửi bù các cập nhật còn thiếu                   |
| Dữ liệu một máy bị hỏng             | Khôi phục từ checkpoint và nhật ký, hoặc chép từ máy nguồn |

## Kiểm thử

```powershell
python -m compileall -q common server client scripts tests
python -X warn_default_encoding -W error::EncodingWarning -m unittest discover -s tests -v
```

CI (GitHub Actions) chạy các lệnh trên cùng `scripts.check_env` trên Windows với Python 3.12 và 3.10 cho mỗi pull request vào `develop` và `main`. Chạy một phần và cách viết test: [docs/development.md](docs/development.md#kiểm-thử).

## Cấu trúc thư mục

```
inventory-update-propagation/
├── common/       Định dạng thông điệp, cấu hình, lược đồ CSDL dùng chung
├── server/       Máy chủ: truyền thông, bản chính, lan truyền, heartbeat, phục hồi
├── client/       Ứng dụng Tkinter và các tab chức năng
├── scripts/      Bật nhanh 4 máy chủ, kiểm tra môi trường
├── tests/        Kiểm thử tự động
└── docs/         Hình ảnh và tài liệu
```

## Tài liệu

| Tài liệu                                     | Nội dung                                                                    |
| -------------------------------------------- | --------------------------------------------------------------------------- |
| [docs/setup.md](docs/setup.md)               | Cài đặt, cấu hình, chạy hệ thống, demo nhiều laptop, lỗi thường gặp         |
| [docs/development.md](docs/development.md)   | Quy tắc Git và commit, quyền sửa file, quy tắc code, kiểm thử và CI         |
| [docs/architecture.md](docs/architecture.md) | Thông điệp, quy tắc áp dụng cập nhật, lan truyền, phục hồi, CSDL, thuật ngữ |

## Thành viên

| Thành viên               | MSSV     | Phụ trách                                          |
| ------------------------ | -------- | -------------------------------------------------- |
| _[Họ tên]_ (trưởng nhóm) | _[MSSV]_ | Truyền thông tin cậy, lược đồ CSDL, điều phối nhóm |
| _[Họ tên]_               | _[MSSV]_ | Bản chính và giao tác (S1)                         |
| _[Họ tên]_               | _[MSSV]_ | Lan truyền tức thời S1 → S2, heartbeat             |
| _[Họ tên]_               | _[MSSV]_ | Lan truyền trễ S1 → S3, S2 → S4                    |
| _[Họ tên]_               | _[MSSV]_ | Phục hồi dữ liệu                                   |

---

Đồ án học tập, Học viện Công nghệ Bưu chính Viễn thông cơ sở TP.HCM.
