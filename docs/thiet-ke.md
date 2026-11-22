# Thiết kế hệ thống

## 1. Phạm vi và yêu cầu

### Bài toán

Client thực hiện các thao tác quản lý sản phẩm, nhập kho, xuất kho và tồn kho trên node S1. Cập nhật được lan truyền từ S1 sang S2 **ngay lập tức** và sang S3 **sau n giây**. Từ S2, cập nhật được lan truyền sang S4 **sau mỗi n lần cập nhật**; giá trị n có thể thay đổi. Hệ thống phải phục hồi được khi dữ liệu tại một node bị tổn hại.

### Yêu cầu chức năng

| ID | Yêu cầu |
|---|---|
| FR-01 | Tạo sản phẩm, nhập kho, xuất kho (số lượng dương), truy vấn tồn kho; tồn kho không bao giờ âm. |
| FR-02 | Chỉ primary (mặc định S1) nhận thao tác ghi. Dữ liệu nghiệp vụ, operation log, outbox và kết quả chống trùng request được ghi nguyên tử. |
| FR-03 | S1 → S2 không có độ trễ chủ động; client không chờ ACK của S2. |
| FR-04 | S1 → S3 chỉ gửi **sau tối thiểu** n giây kể từ khi commit. |
| FR-05 | S2 → S4 chỉ gửi khi đủ n cập nhật liên tục mới. Retry không được tính là cập nhật; phần còn lại ít hơn n tiếp tục chờ. |
| FR-06 | `delaySeconds` và `batchSize` là hai tham số cấu hình độc lập. |
| FR-07 | Mỗi replica phục vụ truy vấn đọc, kèm node ID và vị trí đã áp dụng; dữ liệu có thể chưa cập nhật mới nhất. |
| FR-08 | Có retry, chống trùng, phát hiện thiếu sự kiện (gap), catch-up và phục hồi dữ liệu. |
| FR-09 | Khi primary gặp sự cố, người vận hành dùng một lệnh promote để đưa node còn hoạt động có dữ liệu mới nhất lên làm primary. Không bao giờ có hai node cùng nhận ghi; các thao tác ghi bị mất được ghi nhận rõ ràng. |
| FR-10 | Cùng một mã nguồn chạy được 4 node trên một máy hoặc trên 4 máy vật lý. |

**Ngoài phạm vi:** failover tự động (tự phát hiện sự cố và tự promote), đồng thuận Raft/Paxos, nhiều node ghi, replication có sẵn của PostgreSQL, snapshot, sharding, message broker (Kafka, RabbitMQ), Kubernetes, giao diện web, client đồ họa (JavaFX), TLS cho gRPC, đóng gói node bằng Docker.

### Quyết định về yêu cầu

| Vấn đề | Quyết định |
|---|---|
| S1 có chờ ACK của S2 trước khi trả kết quả cho client không? | Không |
| Client có được đọc tại replica không? | Có, ở mọi node, kèm `node_id` và `last_applied_seq`. Chỉ primary nhận ghi; replica từ chối ghi |
| `delaySeconds` và `batchSize` có dùng chung một giá trị n không? | Không, hai tham số độc lập (mặc định 5 giây và 3 sự kiện), thay đổi được khi đang chạy |
| Tạo sản phẩm có tính là một cập nhật đối với S4 không? | Có: mỗi sự kiện đã commit là một cập nhật; lệnh bị từ chối không tạo sự kiện |
| Chỉ S1 sập | Promote thủ công: node mới nhất trong S2, S3, S4 lên làm primary |
| S1 và S2 cùng sập | Promote thủ công: node mới nhất trong S3, S4 lên làm primary |
| Chỉ S3 hoặc S4 sập | Hệ thống hoạt động bình thường; node đó bắt kịp khi hoạt động trở lại |
| Có failover tự động không? | Không. Hệ thống không phân biệt được node chết hẳn với node chỉ mất kết nối mạng, nên promote luôn do người vận hành kích hoạt |
| Phục hồi dữ liệu bị tổn hại | Replay operation log từ node nguồn; không dùng snapshot |
| Có cam kết RPO không? | Không. Các thao tác ghi chưa kịp nhân bản trước khi primary sập có thể mất, và được liệt kê khi primary cũ hoạt động trở lại |

## 2. Kiến trúc

```text
Client CLI ──gRPC──> S1 PRIMARY ──ngay lập tức──> S2 REPLICA ──mỗi n cập nhật──> S4 REPLICA
                         └──────── sau n giây ─────────> S3 REPLICA
```

- Mỗi node là một JVM độc lập với một PostgreSQL instance riêng; cả bốn node dùng chung một mã nguồn. Vai trò, cổng, cơ sở dữ liệu và địa chỉ peer được xác định qua cấu hình.
- Client và các node khác không truy cập trực tiếp cơ sở dữ liệu của một node.

| Module | Trách nhiệm |
|---|---|
| `contracts` | Hợp đồng gRPC (`.proto`), nguồn chuẩn của giao thức |
| `common-domain` | Sự kiện domain và port, không phụ thuộc gRPC |
| `node-server` | Server của S1..S4 |
| `cli-client` | Client dòng lệnh |

Mỗi package trong `node-server` (`edu.inventory.node.*`) tự quản lý repository JDBC riêng:

| Package | Trách nhiệm |
|---|---|
| `config` | Cấu hình node |
| `grpc` | Vòng đời gRPC server và các adapter. Không chứa SQL hay quy tắc nghiệp vụ. Mọi bean `BindableService` được đăng ký tự động |
| `inventory` | Nghiệp vụ và transaction ghi tại primary |
| `replication` | Dispatch outbox, apply có thứ tự, ACK/retry, catch-up |
| `policy` | Chính sách lan truyền: gửi ngay, trễ n giây, theo lô n sự kiện |
| `recovery` | Phục hồi dữ liệu, kiểm tra nhất quán, trạng thái node |

Log của mỗi node mang nhãn `[inventory-node-Sx]`.

### Transaction ghi tại primary

1. Kiểm tra `request_id`; từ chối nếu node không phải primary của epoch hiện hành.
2. Khóa `node_state` (`SELECT ... FOR UPDATE`) để tuần tự hóa thứ tự ghi.
3. Áp dụng thay đổi nghiệp vụ. Xuất kho dùng `UPDATE` có điều kiện `quantity >= số lượng xuất`.
4. Cấp `seq = last_issued_seq + 1` trong cùng khóa đó, nên rollback không tạo khoảng trống.
5. Ghi `operation_log` (kèm `request_id`), outbox cho các replica nhận trực tiếp từ primary, `processed_requests`; cập nhật `node_state`; commit. Chỉ trả kết quả cho client sau khi commit.

Outbox của route S3 được gán `eligible_at >= thời điểm commit + n`: sự kiện có thể gửi muộn hơn, nhưng không bao giờ gửi sớm hơn. Sau khi khởi động lại, các sự kiện chưa được lập lịch sẽ được lập lịch bổ sung.

### Transaction apply tại replica

Các bước theo thứ tự:

1. Xác thực nguồn gửi; sự kiện thuộc epoch cũ hơn epoch hiện hành bị từ chối (`STALE_EPOCH`).
2. Khóa `node_state`.
3. So `seq` của sự kiện với tiến độ hiện tại:
   - đã có → trả `DUPLICATE`, hoặc `CONFLICT` nếu nội dung khác;
   - nhảy cóc → trả `GAP_DETECTED`;
   - đúng thứ tự → áp dụng nghiệp vụ, ghi log, ghi `processed_requests` theo `request_id` của sự kiện, tăng `last_applied_seq`. Nếu node này là nguồn của S4, ghi thêm outbox cho S4.
4. Commit, rồi ACK.

S2 chuyển tiếp nguyên vẹn `event_id` và `(epoch, seq)` gốc. Mọi node đều lưu đầy đủ operation log và `processed_requests`, nên bất kỳ node nào cũng có thể trở thành primary mà không làm mất khả năng chống trùng request.

### Schema

Định nghĩa tại [`V1__initial_schema.sql`](../node-server/src/main/resources/db/migration/V1__initial_schema.sql).

- **Bảng nghiệp vụ:** `products`, `stock_in`, `stock_out`, `inventory`.
- **Bảng kỹ thuật:** `node_state`, `operation_log` (`UNIQUE(epoch, seq)`), `outbox`, `replication_progress`, `processed_requests`, `replication_policy` (mặc định: trễ 5 giây, lô 3 sự kiện), `snapshot_metadata`.

Hiện mới có `node_state` được code đọc (qua `GetStatus`). Các bảng còn lại đã được tạo nhưng chưa có code ghi. `snapshot_metadata` không được sử dụng, vì phục hồi dựa trên replay operation log. Operation log được giữ vĩnh viễn.

Migration đã phát hành không được sửa; mọi thay đổi schema là một migration mới.

## 3. Giao thức

Định nghĩa tại [`inventory.proto`](../contracts/src/main/proto/inventory.proto).

| Service | RPC | Trạng thái |
|---|---|---|
| `NodeService` | `GetStatus` | Đã triển khai. Trường `status` hiện luôn là `READY` khi cơ sở dữ liệu của node trả lời |
| `NodeService` | `UpdatePolicy` | Chưa triển khai (trả `UNIMPLEMENTED`) |
| `InventoryService` | `ExecuteCommand`, `GetInventory` | Chưa triển khai (trả `UNIMPLEMENTED`) |
| `ReplicationService` | `ApplyEvents`, `FetchEvents` (limit 1..500) | Chưa triển khai (trả `UNIMPLEMENTED`) |

Trường `ReplicationEvent.event_hash` được dành sẵn; chưa được gán hay kiểm tra, và thuật toán băm chưa được định nghĩa.

**Mã lỗi:**

| Mã | Ý nghĩa |
|---|---|
| `INVALID_ARGUMENT` | Request không hợp lệ |
| `FAILED_PRECONDITION` | Không đủ tồn kho, hoặc node không phải primary |
| `ALREADY_EXISTS` | `request_id` trùng nhưng nội dung khác |
| `UNAVAILABLE` | Không kết nối được peer, hoặc cơ sở dữ liệu của node không khả dụng |
| `DEADLINE_EXCEEDED` | Hết thời gian chờ |

**Quy tắc chung:**

- **Xác nhận (ACK):** gRPC `OK` không đồng nghĩa với việc đã apply. Chỉ `APPLIED` hoặc `DUPLICATE` (đã đối chiếu nội dung) mới được coi là ACK.
- **Thứ tự:**
  - `event_id` là UUID, giữ nguyên qua mọi lần retry;
  - `seq` liên tục trên toàn bộ lịch sử, kể cả qua các lần promote;
  - thứ tự áp dụng là thứ tự commit tại primary, không theo timestamp;
  - `epoch` bắt đầu từ 1 và tăng thêm 1 sau mỗi lần promote.
- **Idempotency phía client:** mỗi lệnh gửi kèm `request_id`. Retry với cùng `request_id` nhận lại kết quả đã lưu. Replica từ chối thao tác ghi.
- **Giao nhận:**
  - at-least-once kết hợp apply idempotent; không có exactly-once;
  - khi timeout hoặc `UNAVAILABLE`, sự kiện không được đánh dấu ACK mà được retry với exponential backoff có jitter;
  - trạng thái retry được lưu trong cơ sở dữ liệu.

| Chính sách | Áp dụng cho | Quy tắc |
|---|---|---|
| Ngay lập tức | S2 | Đủ điều kiện gửi ngay khi commit; gửi theo lô nhỏ, `seq` liên tục. |
| Trễ n giây | S3 | Gửi khi `now >= eligible_at`; n là độ trễ tối thiểu. Thay đổi n chỉ áp dụng cho sự kiện mới. |
| Theo lô n | S4 | Mỗi lô gồm đúng n sự kiện liên tục chưa được ACK; không có cơ chế xả lô theo thời gian. Lô lỗi giữ nguyên thành viên khi retry; giá trị n mới áp dụng từ lô kế tiếp. |

**Nguồn gửi của từng replica:**
- Mỗi replica giữ nguyên chính sách nhận của mình.
- S2 và S3 nhận từ primary.
- S4 nhận từ S2 khi S2 đang là replica hoạt động; ngược lại, S4 nhận từ primary.
- Node từng là primary khi quay lại sẽ nhận theo chính sách "ngay lập tức".

| Tình huống | Tuyến lan truyền |
|---|---|
| Bình thường (S1 là primary) | S1 → S2 ngay lập tức; S1 → S3 trễ n giây; S2 → S4 theo lô n |
| S2 được promote | S2 → S3 trễ n giây; S2 → S4 theo lô n |
| S3 được promote (S1, S2 sập) | S3 → S4 theo lô n |
| S4 được promote (S1, S2 sập) | S4 → S3 trễ n giây |

Thay đổi chính sách (`UpdatePolicy`) được gửi tới primary và được nhân bản như một sự kiện, nên mọi node có cùng cấu hình chính sách.

**Deadline khuyến nghị:**

| RPC | Deadline |
|---|---|
| `GetStatus` | 3 giây |
| `ApplyEvents` | 3–5 giây |
| `FetchEvents` | 5 giây |

Node trả lỗi nhanh khi cơ sở dữ liệu không khả dụng (`DB_CONNECTION_TIMEOUT_MS`, mặc định 2000 ms). Log replication cần có các trường:
- `node_id`, `epoch`
- dải `seq`
- `event_id`, `request_id`
- `retry_count`

## 4. Sự cố và phục hồi

### Bảng sự cố

| Sự cố | Xử lý |
|---|---|
| Timeout hoặc mất ACK | Bên nhận có thể đã commit; retry với cùng `event_id`. |
| S3 hoặc S4 sập | Hệ thống hoạt động bình thường. Sự kiện cho node đó được giữ trong outbox; node bắt kịp từ `last_applied_seq` khi hoạt động trở lại. |
| S2 sập | Primary vẫn nhận ghi; S3 vẫn được cập nhật. S4 tạm dừng nhận cho đến khi S2 hoạt động trở lại, hoặc cho đến khi primary trực tiếp trở thành nguồn của S4. |
| Replica bị crash rồi khởi động lại | Tiếp tục từ tiến độ đã lưu bền; lấy bù các sự kiện còn thiếu. |
| Dữ liệu replica bị tổn hại | Phục hồi bằng replay (xem bên dưới). |
| Primary bị crash, dữ liệu còn nguyên, chưa promote | Khởi động lại; outbox tự tiếp tục. |
| Chỉ S1 sập | Promote thủ công node mới nhất trong S2, S3, S4. |
| S1 và S2 cùng sập | Promote thủ công node mới nhất trong S3, S4. |
| Primary cũ hoạt động trở lại sau khi đã promote | Tự chuyển thành replica và đồng bộ lại (xem bên dưới). |

### Thay thế primary (promote thủ công)

Người vận hành chạy lệnh `promote` sau khi xác nhận primary đã sập. Lệnh thực hiện các bước:

1. **Kiểm tra an toàn:** nếu primary hiện tại vẫn trả lời `GetStatus` thì từ chối promote.
2. **Chọn ứng viên:** node còn hoạt động có `(epoch, last_applied_seq)` cao nhất và lịch sử liên tục, không có gap. Nếu bằng nhau, ưu tiên S2, rồi S3, rồi S4.
3. **Fencing bằng epoch:** ứng viên tăng `epoch` thêm 1 và ghi vào bảng `epoch_history(epoch, start_seq, node_id)`, với `start_seq` là `seq` đầu tiên của epoch mới. Mọi RPC ghi và RPC replication đều kiểm tra epoch; yêu cầu thuộc epoch cũ bị từ chối (`STALE_EPOCH`).
4. **Chuyển vai trò:** ứng viên chuyển `node_state.role` sang `PRIMARY`, rồi bắt đầu nhận ghi với `seq` tiếp nối.
5. **Cấu hình lại tuyến lan truyền** theo bảng ở mục 3. Các replica còn lại bắt kịp từ primary mới qua `FetchEvents`.
6. **Báo cáo:** in ra primary mới, epoch mới, `start_seq`, và `seq` cao nhất mà mỗi node đã có.

Client tìm primary bằng cách gọi `GetStatus` ở các node và chọn node có `role = PRIMARY` với epoch cao nhất.

**Vì sao phải chọn node mới nhất.** Ví dụ: S1 ở `seq` 105, S2 ở 104, S3 ở 100, S4 ở 96. Nếu promote S3, các cập nhật 101–104 mà S2 đã có sẽ bị ghi đè bởi lịch sử mới. Promote S2 thì chỉ mất cập nhật 105, là cập nhật chưa đến được node nào.

**Vì sao không tự động.** Khi không gọi được S1, hệ thống không phân biệt được S1 đã sập hay chỉ mất kết nối mạng. Nếu tự promote trong lúc S1 vẫn nhận ghi, sẽ có hai primary (split-brain). Lệnh `promote` do người vận hành chạy, kèm bước kiểm tra an toàn ở bước 1, loại bỏ rủi ro này trong phạm vi đồ án.

### Primary cũ hoạt động trở lại

1. Khi khởi động, node hỏi `GetStatus` các peer. Thấy epoch cao hơn epoch của mình, node chuyển sang `REPLICA` và không nhận ghi.
2. Các sự kiện của node có `seq ≥ start_seq` của epoch mới là phần **chưa kịp nhân bản trước khi sập**. Những sự kiện này bị loại bỏ, được ghi log và in ra như **danh sách cập nhật đã mất**.
3. Node xóa dữ liệu và replay lại từ primary mới, rồi nhận cập nhật theo chính sách "ngay lập tức".

### Phục hồi dữ liệu bị tổn hại

| Tình huống | Phát hiện | Phục hồi |
|---|---|---|
| Mất cơ sở dữ liệu của replica | Node khởi động với `last_applied_seq = 0` hoặc không khởi động được | Tạo cơ sở dữ liệu mới, replay toàn bộ operation log từ node nguồn |
| Dữ liệu replica bị sửa sai | Kiểm tra bất biến theo yêu cầu: tồn kho của mỗi sản phẩm = tổng nhập − tổng xuất, và không âm | Cách ly node, xóa dữ liệu, replay toàn bộ operation log, kiểm tra lại bất biến |
| Crash hoặc mất mạng | Phát hiện thiếu `seq` | Lấy bù từ `last_applied_seq` qua `FetchEvents` |

Phục hồi không dùng snapshot: operation log được giữ vĩnh viễn trên mọi node. Khôi phục S4 ngoài chính sách lô là thao tác quản trị tường minh, không phải xả ngầm phần sự kiện còn lại.

**Vòng đời trạng thái node (mục tiêu; chưa triển khai, hiện `GetStatus` chỉ trả `READY`):**

```text
READY → SUSPECT → QUARANTINED → RESTORING → VERIFYING → CATCHING_UP → READY
```

Bất kỳ bước kiểm tra nào thất bại đều đưa node về `QUARANTINED`.

## 5. Quyết định kiến trúc

| # | Quyết định | Hệ quả |
|---|---|---|
| 1 | Java 21, Spring Boot non-web, gRPC/Protobuf, Spring JDBC + Flyway; mỗi node một PostgreSQL. Không chọn Java RMI (phụ thuộc Java serialization), TCP thuần (phải tự xây framing và xử lý lỗi), REST (ngoài phạm vi), Kafka (thêm hạ tầng và thay thế phần replication cần tự xây dựng). | gRPC chỉ đảm nhận truyền thông. Lưu bền, idempotency và phục hồi do ứng dụng đảm nhận. |
| 2 | Một node ghi; trả kết quả cho client sau khi commit cục bộ (kèm log và outbox); không chờ ACK của replica; giao nhận at-least-once kết hợp apply idempotent. | Replica có thể trả dữ liệu cũ; S4 có thể giữ phần sự kiện chưa đủ lô vô thời hạn; mất S1 có thể làm mất các ghi chưa nhân bản. Hệ thống không cung cấp nhất quán mạnh. |
| 3 | Thay thế primary bằng lệnh promote thủ công, chọn node mới nhất, fencing bằng epoch; không failover tự động. | Hệ thống ngừng nhận ghi từ lúc primary sập đến khi promote. Đổi lại không có split-brain. Các ghi chưa kịp nhân bản có thể mất và được liệt kê. |
| 4 | Một mã nguồn cho hai môi trường triển khai: cục bộ (4 node trên một máy) và vật lý (mỗi máy một node). Địa chỉ peer lấy từ cấu hình. | Docker là tùy chọn. Triển khai vật lý cần kiểm tra bind IP, firewall và cấu hình peer. |
| 5 | Phục hồi dữ liệu bằng replay operation log; không dùng snapshot. | Operation log được giữ vĩnh viễn trên mọi node. Phù hợp với khối lượng dữ liệu của đồ án. |
| 6 | Phiên bản thư viện và công cụ build chỉ khai báo trong `pom.xml` và Maven Wrapper; thư viện có trong BOM của Spring Boot hoặc gRPC không ghi version riêng. Không nâng Spring Boot, gRPC hay Protobuf trong suốt dự án. Không dùng Lombok; dùng `record` của Java 21. Định dạng mã nguồn theo `.editorconfig`, không thêm plugin format vào build. | Mọi máy và CI build cùng một bộ phiên bản. Nâng phiên bản là một quyết định mới, cần Pull Request riêng. |
| 7 | Xác thực RPC nội bộ bằng token dùng chung trong metadata gRPC (`authorization: Bearer <token>`), cấu hình qua biến môi trường `CLUSTER_TOKEN` và kiểm tra bằng `ServerInterceptor`. Áp dụng cho `ReplicationService` và các RPC quản trị của `NodeService` (`UpdatePolicy`, và `Promote` khi được bổ sung vào hợp đồng). `InventoryService` và `GetStatus` không yêu cầu token. Không dùng mTLS. | Không thay đổi `.proto`. Thiếu hoặc sai token trả `UNAUTHENTICATED`. Token truyền plaintext, nên hệ thống chỉ được triển khai trong LAN tin cậy. |
| 8 | JVM của node do script khởi động dùng `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xms64m -Xmx256m`. Test và CI giữ cấu hình JVM mặc định. | Thời gian khởi động cụm giảm khoảng một nửa. Hiệu năng đỉnh thấp hơn, không ảnh hưởng ở quy mô đồ án. |
| 9 | Client duy nhất là CLI. Không làm client JavaFX, TLS cho gRPC hay đóng gói node bằng Docker; Docker chỉ dùng để chạy PostgreSQL và là tùy chọn. | Nguồn lực tập trung cho các kịch bản nghiệm thu mức Bắt buộc (xem [kiem-thu.md](kiem-thu.md#3-kịch-bản-nghiệm-thu)). |

Quyết định kiến trúc mới được bổ sung vào bảng này thông qua Pull Request.

## 6. Hạn chế đã biết

| Hạn chế | Hướng xử lý |
|---|---|
| `operation_log` chưa có `schema_version` của sự kiện | Migration bổ sung |
| Outbox route S3 chưa lưu `policy_value`/`config_version` tại thời điểm chấp nhận sự kiện | Migration bổ sung |
| Chưa lưu thành viên và ranh giới của lô S2 → S4 đang gửi | Migration bổ sung |
| `processed_requests.event_id` bắt buộc, nên không lưu được kết quả của lệnh bị từ chối | Cho phép `NULL` để retry trả lại cùng kết quả từ chối |
| `ReplicationTransport.DeliveryReceipt` chỉ có cờ `accepted`, chưa phân biệt `GAP_DETECTED`, `CONFLICT`, `STALE_EPOCH` | Thay bằng mã kết quả chi tiết |
| RPC nội bộ chưa được xác thực, truyền plaintext | Xác thực bằng token trong metadata theo quyết định 7; chỉ triển khai trong LAN tin cậy |
| Vai trò node lấy từ cấu hình (`NODE_ROLE`), không đổi được khi đang chạy | Thêm `node_state.role`; `NODE_ROLE` chỉ dùng làm giá trị khởi tạo |
| Chưa có bảng `epoch_history` | Migration bổ sung |
| Sự kiện chưa mang `request_id`, nên replica không thể chống trùng request sau khi được promote | Thêm `request_id` vào `ReplicationEvent` và `operation_log` |
| Chưa có RPC promote và RPC liệt kê tồn kho | Thêm `NodeService.Promote` và `InventoryService.ListInventory` |
| `PolicyUpdateRequest.route_id` gắn với tên node (`S1_TO_S3`, `S2_TO_S4`) | Đổi sang định danh theo chính sách (trễ, theo lô) và nhân bản thay đổi chính sách như một sự kiện |
