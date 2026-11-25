# Thiết kế hệ thống

Tài liệu này mô tả thiết kế của hệ thống. Thay đổi thiết kế được cập nhật vào đây trong cùng Pull Request với code.

## 1. Bài toán và phạm vi

Client thực hiện các thao tác quản lý sản phẩm, nhập kho, xuất kho và tồn kho trên node S1. Cập nhật được lan truyền từ S1 sang S2 **ngay lập tức** và sang S3 **sau n giây**. Từ S2, cập nhật được lan truyền sang S4 **sau mỗi n lần cập nhật**; giá trị n có thể thay đổi. Hệ thống phải phục hồi được khi dữ liệu tại một node bị tổn hại.

| ID | Yêu cầu |
|---|---|
| FR-01 | Tạo sản phẩm, nhập kho, xuất kho (số lượng dương), truy vấn tồn kho; tồn kho không bao giờ âm. |
| FR-02 | Chỉ primary (mặc định S1) nhận thao tác ghi. Dữ liệu nghiệp vụ, operation log và kết quả chống trùng request được ghi nguyên tử; operation log đồng thời là hàng chờ gửi cho replica. |
| FR-03 | S1 → S2 không có độ trễ chủ động; client không chờ ACK của S2. |
| FR-04 | S1 → S3 chỉ gửi **sau tối thiểu** n giây kể từ khi commit. |
| FR-05 | S2 → S4 chỉ gửi khi đủ n cập nhật liên tục mới. Retry không được tính là cập nhật; phần còn lại ít hơn n tiếp tục chờ. |
| FR-06 | `delaySeconds` và `batchSize` là hai tham số độc lập, đổi được khi đang chạy. |
| FR-07 | Mỗi replica phục vụ truy vấn đọc, kèm node ID và vị trí đã áp dụng; dữ liệu có thể chưa mới nhất. |
| FR-08 | Có retry, chống trùng, phát hiện thiếu sự kiện (gap), lấy bù và phục hồi dữ liệu. |
| FR-09 | Khi primary gặp sự cố, người vận hành dùng một lệnh promote để đưa node còn hoạt động có dữ liệu mới nhất lên làm primary. Mỗi epoch chỉ có một primary và chỉ lịch sử của epoch mới nhất được giữ lại: replica từ chối sự kiện của epoch cũ; ghi mà primary cũ nhận khi bị cô lập bị loại bỏ và được liệt kê là đã mất. |
| FR-10 | Cùng một mã nguồn chạy được 4 node trên một máy hoặc trên 4 máy vật lý. |

**Ngoài phạm vi:** failover tự động, đồng thuận Raft/Paxos, nhiều node ghi, replication có sẵn của PostgreSQL, snapshot, sharding, message broker, Kubernetes, giao diện web hoặc JavaFX, TLS cho gRPC, Docker, chạy cụm trên Linux hoặc macOS.

**Cách hiểu đề bài:**

| Vấn đề | Quyết định |
|---|---|
| S1 có chờ ACK của S2 trước khi trả kết quả cho client không? | Không |
| Client có được đọc tại replica không? | Có, ở mọi node, kèm `node_id` và `last_applied_seq`. Chỉ primary nhận ghi |
| `delaySeconds` và `batchSize` có dùng chung một giá trị n không? | Không; mặc định 5 giây và 3 sự kiện |
| Cái gì tính là một cập nhật đối với S4? | Mỗi sự kiện nghiệp vụ đã commit, kể cả tạo sản phẩm. Lệnh bị từ chối không tạo sự kiện; sự kiện hệ thống không được tính |
| Có failover tự động không? | Không. Hệ thống không phân biệt được node chết hẳn với node chỉ mất mạng, nên promote luôn do người vận hành kích hoạt |
| Phục hồi dữ liệu bị tổn hại thế nào? | Replay operation log từ node nguồn; không dùng snapshot |
| Có cam kết không mất dữ liệu (RPO) không? | Không. Ghi chưa kịp nhân bản trước khi primary sập có thể mất, và được liệt kê khi primary cũ quay lại |

## 2. Thuật ngữ

| Thuật ngữ | Nghĩa |
|---|---|
| Primary / replica | Primary là node duy nhất nhận ghi (mặc định S1). Replica nhận sự kiện từ nguồn của nó và chỉ phục vụ đọc |
| Sự kiện | Một thay đổi đã commit tại primary, lưu trong `operation_log`; có `event_id` (UUID) và cặp `(epoch, seq)` |
| Sự kiện nghiệp vụ / hệ thống | Nghiệp vụ: `CREATE_PRODUCT`, `STOCK_IN`, `STOCK_OUT`. Hệ thống: `POLICY_CHANGE` (đổi chính sách), `EPOCH_START` (bắt đầu epoch sau promote) |
| `seq` | Số thứ tự sự kiện do primary cấp, liên tục trên toàn bộ lịch sử, kể cả qua các lần promote |
| `epoch` | Nhiệm kỳ của primary; bắt đầu từ 1, tăng 1 sau mỗi lần promote |
| Con trỏ của đích | `last_acked_seq` của một node đích trong `replication_progress`: vị trí trong `operation_log` mà đích đã xác nhận. Bên gửi gửi các sự kiện có `seq` lớn hơn con trỏ |
| ACK | Replica xác nhận đã áp dụng; `last_applied_seq` trong phản hồi xác nhận mọi sự kiện có `seq` nhỏ hơn hoặc bằng nó |
| Nguồn của replica | Node gửi sự kiện cho replica đó: S2, S3 nhận từ primary; S4 nhận từ S2 (xem mục 4) |
| Lô | n sự kiện nghiệp vụ liên tục gửi cho S4 trong một lần |

## 3. Kiến trúc

```text
Client CLI ──gRPC──> S1 PRIMARY ──ngay lập tức──> S2 REPLICA ──mỗi n cập nhật──> S4 REPLICA
                         └──────── sau n giây ─────────> S3 REPLICA
```

- Mỗi node là một JVM độc lập với một PostgreSQL riêng; cả bốn node dùng chung một mã nguồn. Vai trò, cổng, cơ sở dữ liệu và địa chỉ peer lấy từ cấu hình.
- Client và các node khác không truy cập trực tiếp cơ sở dữ liệu của một node.
- Replication được xây dựng trong ứng dụng; không dùng replication có sẵn của PostgreSQL.

| Module | Trách nhiệm |
|---|---|
| `contracts` | Hợp đồng gRPC (`inventory.proto`), nguồn chuẩn của giao thức |
| `common-domain` | Sự kiện domain và port (`ReplicationTransport`), không phụ thuộc gRPC |
| `node-server` | Server của S1..S4 |
| `cli-client` | Client dòng lệnh |

Package trong `node-server` (`edu.inventory.node.*`); mỗi package tự quản lý repository JDBC của mình:

| Package | Trách nhiệm |
|---|---|
| `config` | Cấu hình node |
| `grpc` | Vòng đời gRPC server và các adapter; không chứa SQL hay quy tắc nghiệp vụ. Mọi bean `BindableService` được đăng ký tự động |
| `core` | Phần bền dùng chung của node: dòng `node_state` (khóa `SELECT ... FOR UPDATE`, cấp `seq` ở primary, tiến độ áp dụng ở replica, vai trò và trạng thái bền) và ghi `operation_log`. Các hàm của luồng ghi chạy trong transaction của nơi gọi (`inventory`, `replication`, `recovery`), không tự mở transaction; riêng bước lưu vai trò lúc khởi động lần đầu tự mở transaction. Chỉ primary cấp được `seq`, chỉ replica ghi nhận được tiến độ áp dụng, và chỉ sau khi sự kiện đã có trong `operation_log` |
| `inventory` | Nghiệp vụ và transaction ghi tại primary |
| `replication` | Gửi sự kiện theo con trỏ của từng đích, áp dụng có thứ tự, ACK, retry, lấy bù |
| `policy` | Ba chính sách lan truyền và thay đổi chính sách |
| `recovery` | Phục hồi dữ liệu, kiểm tra bất biến, promote, quyết định chuyển trạng thái node (`QUARANTINED`, `RESTORING`) |

### Mô hình dữ liệu

Mọi node có cùng schema, quản lý bằng migration Flyway trong `node-server/src/main/resources/db/migration`. Migration đã phát hành không được sửa; mọi thay đổi là một migration mới. Migration V3 xóa `outbox` và `replication_batch` của V1/V2: hàng chờ gửi là `operation_log`, biên lô S4 tính từ log (quyết định 17).

| Bảng | Nội dung |
|---|---|
| `products`, `stock_in`, `stock_out` | Sản phẩm và phiếu nhập/xuất. `voucher_no` do primary sinh: `IN-<epoch>-<seq>`, `OUT-<epoch>-<seq>` |
| `inventory` | Tồn kho hiện tại của mỗi sản phẩm (`CHECK quantity >= 0`) |
| `node_state` | Một dòng: `role`, `epoch`, `last_issued_seq`, `last_applied_seq`, trạng thái node. Được khóa để tuần tự hóa thứ tự ghi. `last_applied_seq` là tiến độ của node; ở primary nó luôn bằng `last_issued_seq`. `role` được lấy từ `NODE_ROLE` ở lần khởi động đầu tiên (cơ sở dữ liệu mới); sau đó `node_state` là nguồn sự thật, promote và tự hạ vai trò đổi `role` tại đây |
| `operation_log` | Mọi sự kiện (`seq` duy nhất, `UNIQUE(epoch, seq)`), kèm `request_id`, `schema_version` và `committed_at` (thời điểm node ghi dòng này, theo đồng hồ của chính node đó: primary lúc commit lệnh, replica lúc áp dụng sự kiện; `ReplicationEvent` không mang thời gian của primary. `NOT NULL`, do ứng dụng ghi từ `Clock` chứ không lấy mặc định của cơ sở dữ liệu). Giữ vĩnh viễn; đồng thời là hàng chờ gửi cho mọi đích |
| `replication_progress` | Mỗi node đích (`peer_id`) một dòng: con trỏ `last_acked_seq` (nguồn sự thật về ACK) và trạng thái retry `retry_count`, `next_retry_at`, `last_error`. Không lưu riêng tiến độ của bên nhận: `last_applied_seq` trong `ApplyReply` hoặc `GetStatus` chỉ dùng để dời con trỏ |
| `processed_requests` | Nội dung request và kết quả theo `request_id`; `event_id` để trống với lệnh bị từ chối |
| `replication_policy` | Một dòng: `delaySeconds` (0..3600, mặc định 5), `batchSize` (1..100, mặc định 3) và version cấu hình |
| `epoch_history` | `epoch`, `start_seq`, `node_id`, nguồn của S4; dựng từ các sự kiện `EPOCH_START`. Epoch 1 không có dòng: bắt đầu từ `seq` 1, nguồn của S4 là S2 |

## 4. Luồng xử lý

Một lệnh ghi đi qua hệ thống như sau:

1. CLI tìm primary (gọi `GetStatus` các node, chọn node `PRIMARY` có epoch cao nhất) và gửi `ExecuteCommand` kèm `request_id`.
2. Primary ghi dữ liệu và sự kiện (`operation_log`) trong một transaction, rồi trả kết quả cho client. Client không chờ replica.
3. Worker của từng đích đọc các sự kiện sau con trỏ của đích đó: gửi cho S2 ngay, cho S3 khi đến hạn.
4. S2 áp dụng sự kiện vào dữ liệu và `operation_log` của mình, rồi trả ACK.
5. Khi sau con trỏ của S4 có đủ n sự kiện nghiệp vụ, worker của S2 gửi cả lô cho S4.

### Ghi tại primary

1. Từ chối nếu node không phải primary của epoch hiện hành (`FAILED_PRECONDITION`).
2. Khóa `node_state` (`SELECT ... FOR UPDATE`) để tuần tự hóa thứ tự ghi.
3. Kiểm tra `request_id` trong cùng khóa: đã xử lý với cùng nội dung thì trả lại kết quả cũ; khác nội dung thì `INVALID_ARGUMENT`.
4. Áp dụng nghiệp vụ. Xuất kho dùng `UPDATE` có điều kiện `quantity >= số lượng xuất`. Lệnh bị từ chối (sản phẩm đã có, chưa có, không đủ tồn kho) chỉ lưu kết quả vào `processed_requests`, không tạo sự kiện.
5. Cấp `seq = last_issued_seq + 1` trong cùng khóa, nên rollback không tạo khoảng trống; `last_issued_seq` và `last_applied_seq` cùng tăng.
6. Ghi `operation_log` (kèm `committed_at`), `processed_requests`; cập nhật `node_state`; commit. Không ghi gì riêng cho từng đích. Sau commit, đánh thức worker gửi.

### Ba chính sách lan truyền

| Chính sách | Đích | Quy tắc |
|---|---|---|
| Ngay lập tức | S2 | Gửi ngay sau commit: các sự kiện sau con trỏ của S2, tối đa 100 sự kiện liên tục mỗi lần. |
| Trễ n giây | S3 | Chỉ gửi phần đầu liên tục của các sự kiện sau con trỏ của S3 có `committed_at + n ≤ now`, so trên đồng hồ của chính node gửi; n là `delaySeconds` có hiệu lực tại sự kiện đó. Không lưu lịch gửi: sau khi khởi động lại, điều kiện được tính lại từ log. Sau promote, sự kiện primary mới nhận từ primary cũ có `committed_at` là lúc nó áp dụng, muộn hơn lúc commit gốc, nên S3 vẫn nhận trễ ít nhất n giây. |
| Theo lô n | S4 | Mỗi lô gồm đúng n sự kiện nghiệp vụ liên tục, bắt đầu ngay sau lô trước và kết thúc tại sự kiện nghiệp vụ thứ n; sự kiện hệ thống nằm trong khoảng đó đi cùng lô. n là `batchSize` có hiệu lực tại sự kiện nghiệp vụ đầu tiên của lô; `POLICY_CHANGE` nằm giữa một lô đang gom chỉ áp dụng từ lô sau. Cả lô được gửi trong một lần gọi `ApplyEvents` và S4 áp dụng cả lô trong một transaction, nên S4 không bao giờ ở trạng thái nửa lô. Không xả lô theo thời gian. Biên lô tính từ `operation_log` và con trỏ của S4, nên retry luôn gửi lại đúng lô cũ. S4 bắt kịp hoặc replay cũng chỉ đến hết lô đầy đủ cuối cùng; phần lẻ tiếp tục chờ. |

`UpdatePolicy` được gửi tới primary và tạo sự kiện `POLICY_CHANGE`, nên mọi node có cùng cấu hình. Giá trị hợp lệ: `delaySeconds` 0..3600, `batchSize` 1..100; ngoài khoảng trả `INVALID_ARGUMENT`. Giá trị mới chỉ áp dụng cho sự kiện mới (tuyến S3) và từ lô kế tiếp (tuyến S4).

**Tuyến lan truyền:**

- Mỗi replica giữ nguyên chính sách nhận: S2 ngay, S3 trễ n giây, S4 theo lô. Primary cũ sau khi quay lại nhận ngay.
- Primary gửi trực tiếp cho mọi node còn lại, trừ S4 khi S4 đang nhận qua S2.
- Nguồn của S4 được xác định khi promote, ghi trong `EPOCH_START` và cố định trong suốt epoch: là S2 nếu lúc promote S2 còn hoạt động (là replica hoặc chính là primary mới), ngược lại là primary mới. Ở epoch 1, nguồn của S4 là S2.
- Nguồn của S4 sập giữa epoch thì S4 chờ; nguồn không tự thay đổi.
- Khi trở thành nguồn của một replica (sau promote), node gọi `GetStatus` của replica đó và đặt con trỏ `= min(last_applied_seq, start_seq − 1)`, với `start_seq` là `seq` của `EPOCH_START` của epoch hiện tại. Nhờ vậy replica luôn nhận lại `EPOCH_START`: nếu nó đang giữ sự kiện của primary cũ tại `seq` đó (ghi đã mất), nội dung khác nhau sinh `CONFLICT` và node được phục hồi bằng replay thay vì lệch dữ liệu âm thầm.

| Tình huống | Tuyến |
|---|---|
| Bình thường (S1 là primary) | S1 → S2 ngay; S1 → S3 trễ n giây; S2 → S4 theo lô |
| S2 được promote | S2 → S3 trễ n giây; S2 → S4 theo lô |
| S3 được promote, S2 còn hoạt động | S3 → S2 ngay; S2 → S4 theo lô |
| S3 được promote (S1, S2 sập) | S3 → S4 theo lô |
| S4 được promote (S1, S2 sập) | S4 → S3 trễ n giây |

Node quay lại sau khi đã có promote (S1, hoặc S2 khi không còn là nguồn của S4) nhận ngay từ primary.

### Áp dụng tại replica

1. Kiểm tra **epoch của bên gửi** (`EventBatch.epoch`): nhỏ hơn epoch node đang biết thì từ chối cả lô (`STALE_EPOCH`); lớn hơn thì node ghi nhận epoch mới. Sự kiện bên trong lô có thể thuộc epoch cũ (lịch sử trước promote) và vẫn hợp lệ.
2. Khóa `node_state`, so `seq` của sự kiện với tiến độ hiện tại:
   - đã có: `DUPLICATE`, hoặc `CONFLICT` nếu nội dung khác (so sánh trực tiếp các trường của sự kiện); `CONFLICT` đưa node sang `QUARANTINED`;
   - nhảy cóc: `GAP_DETECTED`, trạng thái không đổi;
   - đúng thứ tự: áp dụng nghiệp vụ, ghi log (`committed_at` theo đồng hồ của replica) và `processed_requests`, tăng `last_applied_seq`. Node là nguồn của S4 không ghi gì thêm: worker tuyến S4 đọc thẳng `operation_log` của node theo con trỏ của S4.
3. Commit, rồi trả `ApplyReply` kèm `last_applied_seq` hiện tại.

Replica chuyển tiếp nguyên vẹn `event_id` và `(epoch, seq)` gốc. Mọi node lưu đầy đủ operation log và `processed_requests`, nên node nào được promote cũng giữ được khả năng chống trùng request.

### Giao nhận và retry

- At-least-once kết hợp áp dụng idempotent; không có exactly-once. `event_id` giữ nguyên qua mọi lần retry; thứ tự áp dụng là thứ tự `seq`, không theo timestamp.
- Worker gửi được đánh thức bằng tín hiệu trong JVM sau mỗi commit, kèm polling dự phòng mỗi 500 ms. Tuyến S2, S3: mỗi lần gọi `ApplyEvents` gửi tối đa 100 sự kiện liên tục. Tuyến S4: mỗi lần gọi là đúng một lô; khi có sự kiện hệ thống xen giữa, lô có thể vượt 100 sự kiện.
- Chỉ `APPLIED` hoặc `DUPLICATE` mới là ACK; gRPC `OK` không đồng nghĩa với đã áp dụng. ACK cộng dồn chỉ là dời con trỏ: `replication_progress.last_acked_seq = min(last_applied_seq trong phản hồi, seq lớn nhất vừa gửi)`.
- Khi timeout hoặc `UNAVAILABLE`: không dời con trỏ, retry với exponential backoff (bắt đầu 500 ms, nhân đôi, tối đa 30 giây, jitter ±20%). Trạng thái retry lưu trong `replication_progress`. Node gửi crash giữa lúc gửi thì khởi động lại gửi tiếp từ con trỏ; bên nhận áp dụng idempotent nên không trùng.
- **Lấy bù do bên gửi chủ động.** Nhận `GAP_DETECTED` thì bên gửi đặt con trỏ về `last_applied_seq` mà bên nhận báo và gửi tiếp; `operation_log` giữ vĩnh viễn nên luôn còn dữ liệu để gửi lại, kể cả khi tiến độ bên nhận thấp hơn phần đã ACK (bên nhận bị lùi). Replica khởi động lại cũng được bắt kịp theo cách này, không tự kéo. `FetchEvents` chỉ dùng để replay toàn bộ khi phục hồi (mục 6).
- Nhận `CONFLICT`: bên gửi ngừng gửi cho node đó và ghi log lỗi; node nhận đã chuyển sang `QUARANTINED`.
- Nhận `STALE_EPOCH`: bên gửi thuộc epoch cũ; nếu là primary thì tự chuyển sang replica (mục 6).

## 5. Giao thức

Nguồn chuẩn: [`inventory.proto`](../contracts/src/main/proto/inventory.proto), package `inventory.v1`. Các trường không còn dùng (`event_hash`, `snapshot_required`, `CommandRequest.voucher_no`, `PolicyUpdateRequest.route_id`) được đánh dấu `reserved`. Hợp đồng hiện tại là bản v2 nhưng vẫn giữ package `inventory.v1`: chưa có client nào ngoài repo, nên trường bị bỏ được `reserved` thay vì đổi tên package.

| Service | RPC | Mục đích | Cần token |
|---|---|---|---|
| `InventoryService` | `ExecuteCommand` | Tạo sản phẩm, nhập, xuất; chỉ primary nhận | Không |
| | `GetInventory`, `ListInventory` | Tồn kho một sản phẩm hoặc toàn bộ, ở mọi node, kèm `node_id`, `epoch`, `last_applied_seq` | Không |
| `ReplicationService` | `ApplyEvents` | Gửi các sự kiện liên tục (tối đa 100; tuyến S4 là nguyên một lô) kèm epoch của bên gửi; trả `ApplyCode` và `last_applied_seq` | Có |
| | `FetchEvents` | Lấy các sự kiện sau `after_seq`, `limit` 1..500; dùng khi replay để phục hồi | Có |
| `NodeService` | `GetStatus` | Vai trò, epoch, tiến độ, trạng thái node | Không |
| | `UpdatePolicy` | Đổi `delaySeconds` hoặc `batchSize`; tạo `POLICY_CHANGE` | Có |
| | `Promote` | Đưa node lên primary; nhận `expected_epoch`, trả `new_epoch`, `start_seq`, nguồn của S4 | Có |

**Xác thực:** token dùng chung trong metadata `authorization: Bearer <token>`, đọc từ biến môi trường `CLUSTER_TOKEN`, kiểm tra bằng `ServerInterceptor`.

| Mã gRPC | Khi nào |
|---|---|
| `INVALID_ARGUMENT` | Request không hợp lệ; giá trị chính sách ngoài khoảng cho phép; `request_id` dùng lại với nội dung khác |
| `NOT_FOUND` | Nhập, xuất hoặc truy vấn sản phẩm chưa có |
| `ALREADY_EXISTS` | Tạo sản phẩm đã có |
| `FAILED_PRECONDITION` | Không đủ tồn kho; node không phải primary; điều kiện promote không thỏa |
| `UNAUTHENTICATED` | Thiếu hoặc sai token |
| `UNAVAILABLE` | Không kết nối được peer; cơ sở dữ liệu của node không khả dụng (chờ tối đa `DB_CONNECTION_TIMEOUT_MS`, mặc định 2000 ms); node đang `QUARANTINED` hoặc `RESTORING` (trừ `GetStatus`) |
| `DEADLINE_EXCEEDED` | Hết thời gian chờ |

| `ApplyCode` | Nghĩa |
|---|---|
| `APPLIED` | Đã áp dụng |
| `DUPLICATE` | Đã có, cùng nội dung; tính là ACK |
| `CONFLICT` | Đã có `seq` đó nhưng nội dung khác; bên nhận chuyển sang `QUARANTINED` |
| `GAP_DETECTED` | Thiếu sự kiện phía trước; bên gửi gửi lại từ `last_applied_seq + 1` |
| `STALE_EPOCH` | Bên gửi thuộc epoch cũ |

**Idempotency phía client:** mỗi lệnh kèm `request_id`; gửi lại cùng `request_id` nhận lại kết quả đã lưu, kể cả lệnh bị từ chối.

**Deadline:** `GetStatus` 3 giây; mọi RPC còn lại (`ExecuteCommand`, `GetInventory`, `ListInventory`, `ApplyEvents`, `FetchEvents`, `UpdatePolicy`, `Promote`) 5 giây.

**Log:** mỗi node mang nhãn `[inventory-node-Sx]`. Log replication có `node_id`, `epoch`, dải `seq`, `event_id`, `request_id`, `retry_count`.

## 6. Sự cố và phục hồi

| Sự cố | Xử lý |
|---|---|
| Timeout hoặc mất ACK | Bên nhận có thể đã commit; retry với cùng `event_id`, áp dụng idempotent nên không nhân đôi. |
| S3 hoặc S4 sập | Hệ thống chạy bình thường; sự kiện cho node đó vẫn nằm trong `operation_log`, con trỏ của node đứng yên. Khi node quay lại, nguồn gửi tiếp từ con trỏ. |
| S2 sập | Primary vẫn nhận ghi; S3 vẫn được cập nhật. S4 dừng nhận cho đến khi S2 quay lại. |
| Primary crash, dữ liệu còn nguyên, chưa promote | Khởi động lại; worker gửi tiếp từ con trỏ của từng đích. |
| Chỉ S1 sập | Promote thủ công node mới nhất trong S2, S3, S4. |
| S1 và S2 cùng sập | Promote thủ công node mới nhất trong S3, S4. |
| Primary cũ quay lại sau khi đã promote | Tự chuyển thành replica, liệt kê cập nhật đã mất, đồng bộ lại. |
| Dữ liệu replica bị tổn hại | Cách ly và phục hồi bằng replay. |

### Promote

Người vận hành chạy lệnh `promote` của CLI sau khi xác nhận primary đã sập. CLI làm bước 1, 2 và 5; node ứng viên làm bước 3, 4 khi nhận `NodeService.Promote`.

1. **Kiểm tra an toàn:** primary hiện tại còn trả lời `GetStatus` thì từ chối promote.
2. **Chọn ứng viên:** trong các node đang `READY`, chọn node có `(epoch, last_applied_seq)` cao nhất. Bằng nhau thì ưu tiên S2, rồi S3, rồi S4.
3. **Fencing bằng epoch:** ứng viên đặt `last_issued_seq = last_applied_seq`, tăng `epoch` thêm 1 và ghi sự kiện `EPOCH_START` (epoch, `start_seq`, node, nguồn của S4) tại `start_seq = last_applied_seq + 1`. Mọi RPC ghi và RPC replication kiểm tra epoch; yêu cầu thuộc epoch cũ bị từ chối.
4. **Chuyển vai trò:** ứng viên đặt `role = PRIMARY` và nhận ghi với `seq` tiếp nối. Mỗi nguồn của epoch mới hỏi `GetStatus` các replica mình phụ trách rồi gửi tiếp (mục 4, tuyến lan truyền).
5. **Báo cáo:** in primary mới, epoch mới, `start_seq` và `seq` cao nhất của từng node.

Phải chọn node mới nhất vì chỉ node đó giữ được nhiều lịch sử nhất. Ví dụ S1 ở `seq` 105, S2 ở 104, S3 ở 100: promote S3 sẽ ghi đè các cập nhật 101–104 mà S2 đã có; promote S2 chỉ mất cập nhật 105, vốn chưa đến được node nào.

### Primary cũ quay lại

1. Khi khởi động, node hỏi `GetStatus` các peer; thấy epoch cao hơn thì chuyển sang `REPLICA` và không nhận ghi. Primary đang chạy mà nhận `STALE_EPOCH` hoặc thấy epoch cao hơn cũng tự chuyển ngay (trường hợp chỉ mất mạng trong lúc promote).
2. Các sự kiện của node có `seq ≥ start_seq` của epoch **ngay sau** epoch của node (tra trong `epoch_history` của primary mới) bị loại bỏ, ghi log và in ra như **danh sách cập nhật đã mất**.
3. Node xóa dữ liệu, replay từ primary mới, rồi nhận theo chính sách "ngay lập tức".

### Phục hồi dữ liệu

| Tình huống | Phát hiện | Phục hồi |
|---|---|---|
| Mất cơ sở dữ liệu của replica | Node khởi động với `last_applied_seq = 0`, giống một node mới | Tạo cơ sở dữ liệu mới (Flyway), rồi replay toàn bộ operation log từ primary qua `FetchEvents` theo các bước bên dưới |
| Dữ liệu replica bị sửa sai | Kiểm tra bất biến khi khởi động và mỗi 30 giây: tồn kho = tổng nhập − tổng xuất, và không âm | Cách ly node, xóa dữ liệu, replay toàn bộ log, kiểm tra lại |
| Crash hoặc mất mạng | Bên nhận trả `GAP_DETECTED` hoặc nguồn chưa nhận được ACK | Nguồn gửi lại từ `last_applied_seq + 1` |

Riêng S4, replay chỉ đến hết lô đầy đủ cuối cùng; phần lẻ tiếp tục chờ theo chính sách lô.

**Kích hoạt và tiếp tục replay:**

1. Replica khởi động với `last_applied_seq = 0` chuyển sang `RESTORING`, hỏi `GetStatus` các peer cho tới khi tìm được primary, rồi replay qua `FetchEvents`. Primary có `last_issued_seq = 0` thì không có gì để replay và node về `READY` ngay. Primary không tự replay.
2. Bắt đầu phục hồi (cả khi bị cách ly): xóa dữ liệu nghiệp vụ, đặt `last_applied_seq = 0` và `status = RESTORING` trong cùng một transaction.
3. Mỗi lô `FetchEvents` được áp dụng như tại replica (mục 4): theo thứ tự `seq`, trong transaction, tăng `last_applied_seq`.
4. Replay bị gián đoạn (crash, mất kết nối tới primary): node khởi động lại vẫn ở `RESTORING` và tiếp tục từ `last_applied_seq + 1`. Dữ liệu đã replay là một tiền tố hợp lệ của lịch sử nên không cần xóa lại.
5. Replay xong và kiểm tra bất biến đạt thì về `READY`; từ đó nguồn gửi tiếp như bình thường.

**Trạng thái node:**

| Trạng thái | Khi nào | Phục vụ đọc | Được làm ứng viên promote |
|---|---|---|---|
| `READY` | Hoạt động bình thường | Có (dữ liệu có thể chưa mới nhất) | Có |
| `QUARANTINED` | Kiểm tra bất biến thất bại, hoặc nhận `CONFLICT` | Không (`UNAVAILABLE`) | Không |
| `RESTORING` | Đang xóa dữ liệu và replay; lưu bền trong `node_state` | Không (`UNAVAILABLE`) | Không |

Chuyển trạng thái: `READY → QUARANTINED → RESTORING → READY`. Node chỉ về `READY` khi replay xong và kiểm tra bất biến đạt; thất bại thì quay lại `QUARANTINED`. `GetStatus` luôn trả lời ở mọi trạng thái để người vận hành và CLI biết trạng thái node.

## 7. Quyết định kỹ thuật

Quyết định mới được bổ sung vào bảng này qua Pull Request.

| # | Quyết định | Lý do và đánh đổi |
|---|---|---|
| 1 | Java 21, Spring Boot non-web, gRPC/Protobuf, Spring JDBC + Flyway; mỗi node một PostgreSQL | Hợp đồng rõ ràng, làm song song được. Không chọn RMI (phụ thuộc Java serialization), TCP thuần (tự xây framing), REST (ngoài phạm vi), Kafka (thay mất phần replication cần tự làm) |
| 2 | Một node ghi; trả kết quả sau commit cục bộ; không chờ ACK replica; at-least-once + áp dụng idempotent | Đúng đề bài. Replica có thể trả dữ liệu cũ; không có nhất quán mạnh |
| 3 | Promote thủ công node mới nhất, fencing bằng epoch; không failover tự động | Không có hai lịch sử song song: replica từ chối epoch cũ, ghi mà primary cũ nhận khi bị cô lập bị loại bỏ. Đổi lại hệ thống ngừng nhận ghi từ lúc primary sập đến khi promote |
| 4 | Một mã nguồn cho 4 node trên một máy và 4 máy vật lý; địa chỉ peer lấy từ cấu hình | Triển khai vật lý cần kiểm tra bind IP, firewall, cấu hình peer |
| 5 | Phục hồi bằng replay operation log, không snapshot | Đủ cho khối lượng dữ liệu đồ án; operation log giữ vĩnh viễn trên mọi node |
| 6 | Phiên bản chỉ khai báo trong `pom.xml` và Maven Wrapper, thư viện có trong BOM không ghi version riêng; không nâng Spring Boot, gRPC, Protobuf trong dự án; không dùng Lombok; định dạng theo `.editorconfig` | Mọi máy và CI build cùng một bộ phiên bản |
| 7 | Xác thực RPC nội bộ bằng token dùng chung, không mTLS | Không phải quản lý chứng chỉ, không đổi `.proto`. Token truyền plaintext nên chỉ triển khai trong LAN tin cậy |
| 8 | JVM node do script khởi động dùng `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xms64m -Xmx256m`; test và CI giữ mặc định | Cụm khởi động nhanh gấp khoảng hai lần; hiệu năng đỉnh thấp hơn, không ảnh hưởng ở quy mô đồ án |
| 9 | Client duy nhất là CLI; không làm JavaFX, TLS. Chỉ hỗ trợ Windows: script PowerShell, PostgreSQL cài trực tiếp, không dùng Docker | Dồn sức cho các kịch bản bắt buộc; cả nhóm dùng Windows nên chỉ bảo trì một bộ script. CI vẫn build trên Linux bằng Maven Wrapper |
| 10 | Tuyến lan truyền xác định khi promote và cố định trong epoch; primary thấy epoch cao hơn thì tự hạ vai trò | Mọi node biết cùng một tuyến, không đổi qua lại. Nguồn của S4 sập thì S4 phải chờ |
| 11 | Đổi chính sách và bắt đầu epoch là sự kiện hệ thống trong operation log, không tính vào n của lô S4 | Một cơ chế nhân bản cho cả dữ liệu và cấu hình; logic chia lô phải bỏ qua sự kiện hệ thống |
| 12 | ACK cộng dồn theo `last_applied_seq`; con trỏ trong `replication_progress` là nguồn sự thật | Mất một ACK không gây gửi lại kéo dài; ghi nhận ACK chỉ là cập nhật một dòng |
| 13 | Kiểm tra epoch theo bên gửi (`EventBatch.epoch`), không theo từng sự kiện | Replica nhận đủ lịch sử trước promote từ nguồn mới; primary cũ bị chặn ngay ở mức lô |
| 14 | Lấy bù do bên gửi chủ động: đặt con trỏ về `last_applied_seq` mà bên nhận báo; nguồn mới hỏi `GetStatus` để đặt con trỏ; `FetchEvents` chỉ dùng để replay khi phục hồi | Một cơ chế lấy bù duy nhất. `operation_log` giữ vĩnh viễn nên luôn gửi lại được, kể cả khi bên nhận bị lùi tiến độ |
| 15 | Lô S4 gửi nguyên lô trong một lần gọi `ApplyEvents` và áp dụng trong một transaction; giới hạn 100 sự kiện mỗi lần gọi chỉ áp dụng cho tuyến S2, S3 | S4 không bao giờ ở trạng thái nửa lô, retry đơn giản. Một lần gọi có thể vượt 100 sự kiện khi có sự kiện hệ thống xen giữa; sự kiện nhỏ nên không đáng kể |
| 16 | Replica có `last_applied_seq = 0` tự replay trước khi `READY`; `RESTORING` lưu bền, replay bị gián đoạn tiếp tục từ `last_applied_seq + 1` | Node mất dữ liệu được dựng lại bằng `FetchEvents` trước khi nhận sự kiện mới, thay vì chờ nguồn gửi lại toàn bộ lịch sử. Replay nhiều lần không trùng vì áp dụng theo `seq` trong transaction. Node chờ ở `RESTORING` khi chưa liên lạc được primary |
| 17 | Gửi theo con trỏ của từng đích trên `operation_log` (log shipping), không dùng outbox từng sự kiện | Cùng mô hình replication log của PostgreSQL (LSN), Kafka (offset) và Raft (`nextIndex`); khớp giao thức ghi từ xa dựa trên bản chính và khôi phục theo nhật trình thao tác. Ít bảng, đổi nguồn chỉ là đặt lại con trỏ, crash không để lại trạng thái treo. Đổi lại: độ trễ S3 và biên lô S4 phải tính từ log thay vì lưu sẵn, cần cột `committed_at` |

## 8. Hạn chế đã biết

| Hạn chế | Ghi chú |
|---|---|
| Không có nhất quán mạnh | Replica có thể trả dữ liệu cũ; S4 có thể giữ phần sự kiện chưa đủ lô vô thời hạn |
| Không cam kết không mất dữ liệu | Ghi chưa kịp nhân bản trước khi primary sập sẽ mất; chúng được liệt kê khi primary cũ quay lại |
| Ngừng nhận ghi khi primary sập | Kéo dài đến khi người vận hành chạy `promote` |
| Token truyền plaintext | Chỉ triển khai trong LAN tin cậy; không mở cổng PostgreSQL ra mạng |
| Nguồn của S4 sập giữa epoch thì S4 dừng nhận | Chờ nguồn quay lại hoặc lần promote tiếp theo |
| Kết quả lệnh bị từ chối chỉ lưu ở primary | Sau promote, gửi lại lệnh đó sẽ được xét lại từ đầu |
