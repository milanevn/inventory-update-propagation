"""Chữ ký dùng chung giữa các module: bản ghi cập nhật, dữ liệu thông điệp,
Transport, UpdateLog, ClientNet và NodeContext. Phụ trách: TV1 + TV2.

Module của ai cũng chỉ dựa vào file này, common/protocol.py và common/config.py,
không import module của người khác. Khi test, thay bản thật bằng bản giả trong
common/fakes.py.
"""

import logging
import threading
import time

from common import config
from common.protocol import ErrorCode, MsgType, Op, ProtocolError, make_message

logger = logging.getLogger(__name__)

# Số bản ghi tối đa trong một UPDATE_BATCH hoặc SYNC_RESPONSE, để thông điệp
# luôn dưới giới hạn 1 MB của protocol. Cần gửi nhiều hơn thì chia nhiều lần.
MAX_RECORDS_PER_MESSAGE = 500

# ---------------------------------------------------------------------------
# Bản ghi cập nhật
# ---------------------------------------------------------------------------
# Một bản ghi là một dict {"seq", "op", "data", "created_at"}. S1 điền sẵn mọi
# giá trị, máy sau chép nguyên, không tính lại gì.

RECORD_FIELDS = ("seq", "op", "data", "created_at")

UPDATE_OPS = (Op.ADD_PRODUCT, Op.STOCK_IN, Op.STOCK_OUT)

# Trường bắt buộc trong data theo từng op. unit_price và note được là None.
RECORD_DATA_FIELDS = {
    Op.ADD_PRODUCT: ("product_code", "product_name", "unit"),
    Op.STOCK_IN: ("voucher_no", "product_code", "quantity", "unit_price", "note"),
    Op.STOCK_OUT: ("voucher_no", "product_code", "quantity", "unit_price", "note"),
}


def _is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def validate_record(record):
    """Kiểm tra cấu trúc bản ghi; sai thì ném ValueError. Không kiểm tra nghiệp vụ."""
    if not isinstance(record, dict):
        raise ValueError("Bản ghi phải là dict")
    missing = [f for f in RECORD_FIELDS if f not in record]
    if missing:
        raise ValueError(f"Bản ghi thiếu trường: {', '.join(missing)}")
    seq = record["seq"]
    if not _is_int(seq) or seq < 1:
        raise ValueError(f"seq phải là số nguyên >= 1, nhận {seq!r}")
    op = record["op"]
    if op not in RECORD_DATA_FIELDS:
        raise ValueError(f"op không phải thao tác ghi: {op!r}")
    data = record["data"]
    if not isinstance(data, dict):
        raise ValueError("data của bản ghi phải là dict")
    missing = [f for f in RECORD_DATA_FIELDS[op] if f not in data]
    if missing:
        raise ValueError(f"data của {op} thiếu trường: {', '.join(missing)}")
    if op != Op.ADD_PRODUCT:
        qty = data["quantity"]
        if not _is_int(qty) or qty <= 0:
            raise ValueError(f"quantity phải là số nguyên > 0, nhận {qty!r}")
        price = data["unit_price"]
        if price is not None and (not _is_int(price) or price < 0):
            raise ValueError(
                f"unit_price phải là None hoặc số nguyên >= 0, nhận {price!r}"
            )


# ---------------------------------------------------------------------------
# Dữ liệu (trường data) của từng loại thông điệp
# ---------------------------------------------------------------------------
# Quy tắc chung: mỗi thông điệp gửi đi nhận đúng một trả lời.
#   - Client gửi REQUEST, CONFIG_SET, ADMIN, STATUS_REQUEST: máy chủ trả REPLY
#     (riêng STATUS_REQUEST trả STATUS_RESPONSE). Thao tác ghi gửi tới máy không
#     phải S1 thì trả NACK mã NOT_PRIMARY.
#   - Giữa các máy chủ: trả ACK, NACK, SYNC_RESPONSE hoặc SNAPSHOT_RESPONSE.
#
# REQUEST (op = ADD_PRODUCT, STOCK_IN, STOCK_OUT, GET_STOCK)
#   data theo REQUEST_DATA_FIELDS bên dưới.
#
# REPLY
#   {"ok": bool, "code": None | ErrorCode, "result": dict, "last_seq": int}
#   result của ADD_PRODUCT: {"seq", "product_code"}
#   result của STOCK_IN, STOCK_OUT: {"seq", "voucher_no", "quantity_after", "replicated_to_s2"}
#   result của GET_STOCK: {"items": [{"product_code", "product_name", "unit", "quantity"}]}
#   result của CONFIG_SET: {"batch_n", "period_t_seconds"} (giá trị đang dùng sau khi đổi)
#   result của ADMIN: do TV5 định nghĩa, tối thiểu {"message": str}
#
# UPDATE              data = một bản ghi; seq của thông điệp = seq của bản ghi
# UPDATE_BATCH        {"records": [bản ghi, ...]} tăng dần theo seq
# ACK                 {"last_seq": int}
# NACK                {"code": ErrorCode, "last_seq": int | None, ...}
#                     code = LOG_TRUNCATED thì có thêm "first_seq"
# HEARTBEAT           {"last_seq": int}; trả ACK
# SYNC_REQUEST        {"from_seq": int}
# SYNC_RESPONSE       {"records": [...], "last_seq": int}
#                     máy nguồn đã xóa phần nhật ký cần gửi thì trả NACK LOG_TRUNCATED
# SNAPSHOT_REQUEST    {}
# SNAPSHOT_RESPONSE   kết quả UpdateLog.export_snapshot()
# STATUS_REQUEST      {}
# STATUS_RESPONSE     xem STATUS_FIELDS
# CONFIG_SET          {"batch_n": int | None, "period_t_seconds": float | None};
#                     None = giữ nguyên giá trị đang dùng
# ADMIN               op = CORRUPT_DATA hoặc RECOVER; data do TV5 định nghĩa
#
# MESSAGE_DATA_FIELDS và validate_data() bên dưới là bản kiểm tra được bằng máy
# của bảng này. Thêm trường mới được, đổi nghĩa trường cũ phải báo cả nhóm
# (docs/development.md).

REQUEST_DATA_FIELDS = {
    Op.ADD_PRODUCT: ("product_code", "product_name", "unit"),
    Op.STOCK_IN: ("product_code", "quantity", "unit_price", "note"),
    Op.STOCK_OUT: ("product_code", "quantity", "unit_price", "note"),
    Op.GET_STOCK: ("product_code",),  # None = xem tất cả mặt hàng
}

STATUS_FIELDS = (
    "node_id",
    "is_primary",
    "last_seq",
    "checkpoint_seq",
    "batch_n",  # giá trị đang dùng (đã tính cả CONFIG_SET)
    "period_t_seconds",
    "peers",  # {"S2": {"alive": bool, "last_seen": str | None}, ...}
    "progress",  # {"S2": last_acked_seq, ...} của các máy đích của node này
)

ADMIN_OPS = (Op.CORRUPT_DATA, Op.RECOVER)

# Trường bắt buộc trong data theo loại thông điệp. REQUEST tra theo op
# (REQUEST_DATA_FIELDS); ADMIN chỉ kiểm tra op.
MESSAGE_DATA_FIELDS = {
    MsgType.REPLY: ("ok", "code", "result", "last_seq"),
    MsgType.UPDATE: RECORD_FIELDS,
    MsgType.UPDATE_BATCH: ("records",),
    MsgType.ACK: ("last_seq",),
    MsgType.NACK: ("code", "last_seq"),
    MsgType.HEARTBEAT: ("last_seq",),
    MsgType.SYNC_REQUEST: ("from_seq",),
    MsgType.SYNC_RESPONSE: ("records", "last_seq"),
    MsgType.SNAPSHOT_REQUEST: (),
    MsgType.SNAPSHOT_RESPONSE: ("last_seq", "tables"),
    MsgType.STATUS_REQUEST: (),
    MsgType.STATUS_RESPONSE: STATUS_FIELDS,
    MsgType.CONFIG_SET: ("batch_n", "period_t_seconds"),
    MsgType.ADMIN: (),
}


def validate_data(message):
    """Kiểm tra data của thông điệp đủ trường theo loại; sai thì ném ProtocolError.

    Chỉ kiểm tra hình dạng, không kiểm tra nghiệp vụ. REQUEST có op lạ thì bỏ qua
    để máy chủ trả NACK UNKNOWN_OP như bình thường. Bản giả gọi hàm này cho mọi
    thông điệp đi và về, nên gửi thiếu trường sẽ lộ ngay khi test.
    """
    msg_type, op, data = message.get("type"), message.get("op"), message.get("data")
    if msg_type not in MESSAGE_DATA_FIELDS and msg_type != MsgType.REQUEST:
        raise ProtocolError(f"Loại thông điệp lạ: {msg_type!r}")
    if not isinstance(data, dict):
        raise ProtocolError(f"data của {msg_type} phải là dict")
    if msg_type == MsgType.REQUEST:
        fields = REQUEST_DATA_FIELDS.get(op, ())
    else:
        fields = MESSAGE_DATA_FIELDS[msg_type]
    missing = [f for f in fields if f not in data]
    if missing:
        raise ProtocolError(
            f"data của {msg_type} {op or ''} thiếu: {', '.join(missing)}"
        )
    if msg_type == MsgType.ADMIN and op not in ADMIN_OPS:
        raise ProtocolError(f"ADMIN cần op là một trong {ADMIN_OPS}, nhận {op!r}")
    try:
        if msg_type == MsgType.UPDATE:
            validate_record(data)
            if message.get("seq") != data["seq"]:
                raise ProtocolError("seq của UPDATE phải bằng seq của bản ghi")
        if msg_type in (MsgType.UPDATE_BATCH, MsgType.SYNC_RESPONSE):
            records = data["records"]
            if not isinstance(records, list):
                raise ProtocolError("records phải là danh sách")
            for record in records:
                validate_record(record)
            seqs = [r["seq"] for r in records]
            if seqs != sorted(set(seqs)):
                raise ProtocolError("records phải tăng dần theo seq, không trùng")
    except ValueError as exc:
        raise ProtocolError(str(exc)) from exc


# --- Hàm tạo thông điệp (dùng thay vì tự ghép data) ---


def make_reply(sender, ok, result=None, code=None, last_seq=None):
    """REPLY gửi về client."""
    return make_message(
        MsgType.REPLY,
        sender,
        data={"ok": ok, "code": code, "result": result or {}, "last_seq": last_seq},
    )


def make_ack(sender, last_seq):
    return make_message(MsgType.ACK, sender, data={"last_seq": last_seq})


def make_nack(sender, code, last_seq=None, **extra):
    data = {"code": code, "last_seq": last_seq}
    data.update(extra)
    return make_message(MsgType.NACK, sender, data=data)


def make_update(sender, record):
    """UPDATE mang một bản ghi (S1 -> S2)."""
    return make_message(MsgType.UPDATE, sender, data=record, seq=record["seq"])


def make_update_batch(sender, records):
    """UPDATE_BATCH mang nhiều bản ghi tăng dần theo seq (S1 -> S3, S2 -> S4).

    Tối đa MAX_RECORDS_PER_MESSAGE bản ghi; nhiều hơn thì gọi nhiều lần.
    """
    if len(records) > MAX_RECORDS_PER_MESSAGE:
        raise ValueError(
            f"Lô {len(records)} bản ghi vượt {MAX_RECORDS_PER_MESSAGE}; hãy chia nhỏ"
        )
    return make_message(MsgType.UPDATE_BATCH, sender, data={"records": list(records)})


def make_heartbeat(sender, last_seq):
    return make_message(MsgType.HEARTBEAT, sender, data={"last_seq": last_seq})


def make_sync_request(sender, from_seq):
    return make_message(MsgType.SYNC_REQUEST, sender, data={"from_seq": from_seq})


# ---------------------------------------------------------------------------
# Lỗi dùng chung
# ---------------------------------------------------------------------------


class SeqGapError(Exception):
    """seq nhận được lớn hơn last_seq + 1: thiếu cập nhật ở giữa."""

    def __init__(self, last_seq, got_seq):
        super().__init__(f"Thiếu cập nhật: last_seq={last_seq}, nhận seq={got_seq}")
        self.last_seq = last_seq
        self.got_seq = got_seq


class LogTruncated(Exception):
    """Phần nhật ký cần đọc đã bị xóa sau checkpoint; chỉ còn từ first_seq trở đi."""

    def __init__(self, from_seq, first_seq):
        super().__init__(
            f"Nhật ký chỉ còn từ seq={first_seq}, không đọc được từ seq={from_seq}"
        )
        self.from_seq = from_seq
        self.first_seq = first_seq


class NodeUnreachable(Exception):
    """Gửi đủ max_retries lần mà máy đích vẫn không trả lời."""

    def __init__(self, target):
        super().__init__(f"Không liên lạc được với {target}")
        self.target = target


# ---------------------------------------------------------------------------
# Transport: truyền thông tin cậy giữa các máy chủ (TV1, server/transport.py)
# ---------------------------------------------------------------------------


class Transport:
    """Gửi/nhận thông điệp tin cậy giữa các máy chủ qua TCP.

    Cũng là phía nghe của máy chủ với client: ClientNet gửi tới cổng mà serve() mở.
    """

    def serve(self, handler):
        """Bắt đầu nghe ở cổng của node này (chạy nền, trả về ngay).

        Mỗi thông điệp đến gọi handler(msg) -> reply. Thông điệp gửi lại với
        msg_id đã xử lý thì trả lại đúng reply cũ, không gọi handler lần nữa
        (nhớ trong RAM; mất khi tắt máy, nên S1 còn dựa vào processed_requests).
        """
        raise NotImplementedError

    def request(self, target, message):
        """Gửi message tới node target ("S1".."S4") và chờ trả lời.

        Chờ ack_timeout_seconds mỗi lần; hết thì gửi lại cùng msg_id. Gửi tối đa
        max_retries lần (tính cả lần đầu), vẫn không có trả lời thì ném NodeUnreachable.
        """
        raise NotImplementedError

    def close(self):
        """Ngừng nghe, đóng mọi kết nối. Gọi nhiều lần không lỗi."""
        raise NotImplementedError


class ClientNet:
    """Phía client (TV1, client/net.py): gửi một yêu cầu tới một máy chủ, chờ trả lời."""

    def request(self, target, message):
        """Mở kết nối mới tới target, gửi message, trả về thông điệp trả lời.

        Gửi lại cùng msg_id như Transport.request; hết max_retries lần thì ném
        NodeUnreachable.
        """
        raise NotImplementedError


# ---------------------------------------------------------------------------
# UpdateLog: dữ liệu và nhật ký của một máy (TV2, server/db.py)
# ---------------------------------------------------------------------------


class UpdateLog:
    """Nhật ký cập nhật, dữ liệu kho và trạng thái nhân bản của một máy, trên file SQLite của máy đó.

    Đây là lối duy nhất để đọc/ghi CSDL. Mọi hàm an toàn khi gọi từ nhiều luồng.
    tests/test_fakes.py có UpdateLogContract: bản thật phải qua cùng bộ test với FakeLog.
    """

    # --- Nhật ký và áp dụng cập nhật ---

    def last_seq(self):
        """seq cuối cùng máy này đã áp dụng (0 nếu chưa có)."""
        raise NotImplementedError

    def first_seq(self):
        """seq nhỏ nhất còn đọc được trong nhật ký.

        Nhật ký chưa bị xóa lần nào thì trả 1.
        """
        raise NotImplementedError

    def read_from(self, from_seq, limit=None):
        """Các bản ghi có seq >= from_seq, tăng dần theo seq, tối đa limit bản.

        from_seq nhỏ hơn first_seq() thì ném LogTruncated. from_seq > last_seq() thì trả [].
        """
        raise NotImplementedError

    def apply_update(self, record, processed=None):
        """Áp dụng một bản ghi trong một giao tác.

        True: đã áp dụng; False: seq trùng, bỏ qua; seq nhảy cóc thì ném SeqGapError.
        Bản ghi sai cấu trúc hoặc làm sai nghiệp vụ (tồn âm, mã hàng không có, trùng
        mã hàng) thì ném ValueError và không thay đổi gì.
        processed=(msg_id, reply) chỉ S1 truyền, được ghi trong cùng giao tác.
        """
        raise NotImplementedError

    def get_processed(self, msg_id):
        """REPLY đã lưu cho msg_id (S1), hoặc None nếu chưa xử lý."""
        raise NotImplementedError

    # --- Đọc dữ liệu kho ---

    def get_product(self, product_code):
        """{"product_code", "product_name", "unit", "quantity"} hoặc None."""
        raise NotImplementedError

    def stock(self, product_code=None):
        """Danh sách {"product_code", "product_name", "unit", "quantity"}, sắp theo product_code.

        product_code khác None thì chỉ trả mặt hàng đó (danh sách rỗng nếu không có).
        """
        raise NotImplementedError

    # --- Tiến độ lan truyền (replication_progress) ---

    def get_progress(self, target):
        """last_acked_seq của máy đích target (0 nếu chưa có)."""
        raise NotImplementedError

    def set_progress(self, target, last_acked_seq):
        """Lưu last_acked_seq của target."""
        raise NotImplementedError

    # --- Trạng thái máy (node_state) ---

    def get_runtime_config(self):
        """{"batch_n": int | None, "period_t_seconds": float | None}.

        None nghĩa là chưa đổi khi đang chạy, dùng giá trị trong config.json.
        """
        raise NotImplementedError

    def set_runtime_config(self, batch_n=None, period_t_seconds=None):
        """Lưu N, T đổi bằng CONFIG_SET. Tham số None thì giữ nguyên giá trị đã lưu.

        batch_n không phải số nguyên >= 1 hoặc period_t_seconds <= 0 thì ném ValueError.
        """
        raise NotImplementedError

    def checkpoint_seq(self):
        """seq tại checkpoint gần nhất (0 nếu chưa có)."""
        raise NotImplementedError

    def set_checkpoint_seq(self, seq):
        """Lưu seq của checkpoint vừa tạo."""
        raise NotImplementedError

    def truncate_log(self, before_seq, targets):
        """Xóa bản ghi nhật ký có seq < before_seq, nhưng giữ mọi bản ghi máy đích chưa ACK.

        targets: các máy đích của máy này (config.downstreams). Máy đích chưa có
        dòng tiến độ coi như last_acked_seq = 0, nên chưa xóa được gì.
        Trả về first_seq() sau khi xóa.
        """
        raise NotImplementedError

    # --- Bản chụp toàn bộ dữ liệu (SNAPSHOT) ---

    def export_snapshot(self):
        """{"last_seq": int, "tables": {"products": [...], "stock_in": [...],
        "stock_out": [...], "inventory": [...]}}; mỗi dòng là dict tên cột -> giá trị
        đúng như common/schema.sql, sắp theo khóa chính.
        """
        raise NotImplementedError

    def import_snapshot(self, snapshot):
        """Thay toàn bộ dữ liệu nghiệp vụ bằng snapshot trong một giao tác; xóa nhật ký,
        đặt last_seq = snapshot["last_seq"] và first_seq = last_seq + 1.
        """
        raise NotImplementedError


# ---------------------------------------------------------------------------
# Xử lý dùng chung cho các module nhận/gửi bù cập nhật
# ---------------------------------------------------------------------------


def handle_updates(log, sender, records):
    """Áp dụng lần lượt các bản ghi (UPDATE, UPDATE_BATCH, SYNC_RESPONSE) và trả ACK/NACK.

    Bản ghi trùng được bỏ qua. Dừng ở bản ghi đầu tiên không áp dụng được:
      - thiếu seq ở giữa: NACK SEQ_GAP kèm last_seq; bên gửi gửi lại từ last_seq + 1
      - bản ghi sai cấu trúc hoặc nghiệp vụ: NACK INTERNAL_ERROR kèm last_seq và
        "failed_seq" (không nên xảy ra vì S1 đã kiểm tra; xảy ra là dữ liệu đã lệch)
    """
    for record in records:
        try:
            log.apply_update(record)
        except SeqGapError:
            return make_nack(sender, ErrorCode.SEQ_GAP, log.last_seq())
        except ValueError as exc:
            seq = record.get("seq") if isinstance(record, dict) else None
            logger.error("%s không áp dụng được seq=%s: %s", sender, seq, exc)
            return make_nack(
                sender, ErrorCode.INTERNAL_ERROR, log.last_seq(), failed_seq=seq
            )
    return make_ack(sender, log.last_seq())


def handle_sync_request(log, sender, from_seq, limit=MAX_RECORDS_PER_MESSAGE):
    """Trả lời SYNC_REQUEST: SYNC_RESPONSE với tối đa limit bản ghi từ from_seq,
    hoặc NACK LOG_TRUNCATED kèm first_seq nếu phần đó đã bị xóa.

    Bên xin so last_seq trong trả lời với seq cuối nhận được để biết còn phải xin tiếp.
    """
    try:
        records = log.read_from(from_seq, limit=limit)
    except LogTruncated as exc:
        return make_nack(
            sender, ErrorCode.LOG_TRUNCATED, log.last_seq(), first_seq=exc.first_seq
        )
    return make_message(
        MsgType.SYNC_RESPONSE,
        sender,
        data={"records": records, "last_seq": log.last_seq()},
    )


# ---------------------------------------------------------------------------
# NodeContext: thứ truyền vào start(ctx) của mỗi module máy chủ
# ---------------------------------------------------------------------------


class NodeContext:
    """Mọi thứ một module máy chủ cần. server/main.py tạo một đối tượng cho mỗi node.

    Mỗi module trong start(ctx):
      - đăng ký xử lý loại thông điệp của mình: ctx.on(MsgType.UPDATE, handler)
      - muốn biết khi S1 vừa áp dụng một cập nhật: ctx.on_applied(callback)
      - tạo thread nền thì đặt daemon=True, chờ bằng ctx.stopping.wait(giây)
        thay cho time.sleep, và thoát vòng lặp khi ctx.stopping được đặt
      - lấy thời gian hiện tại bằng ctx.clock() để test thay được bằng FakeClock
    """

    def __init__(self, node_id, cfg, transport, log, clock=time.time):
        self.node_id = node_id
        self.cfg = cfg
        self.transport = transport
        self.log = log
        self.clock = clock
        self.stopping = threading.Event()
        self._handlers = {}
        self._applied_callbacks = []

    @property
    def is_primary(self):
        return self.node_id == self.cfg["primary"]

    @property
    def upstream(self):
        """Máy nguồn của node này (None với S1)."""
        return config.upstream(self.cfg, self.node_id)

    @property
    def downstreams(self):
        """[(máy đích, mode), ...] mà node này phải đẩy cập nhật tới."""
        return config.downstreams(self.cfg, self.node_id)

    def on(self, msg_type, handler):
        """Đăng ký handler(msg) -> reply cho một loại thông điệp. Mỗi loại chỉ một handler."""
        if msg_type in self._handlers:
            raise ValueError(f"{msg_type} đã có handler")
        self._handlers[msg_type] = handler

    def dispatch(self, msg):
        """Gọi handler theo msg["type"]. Đưa cho transport.serve(ctx.dispatch).

        Không có handler: NACK UNKNOWN_OP. Handler ném lỗi: ghi log kèm traceback
        và trả NACK INTERNAL_ERROR, để một module lỗi không làm sập cả máy.
        """
        handler = self._handlers.get(msg.get("type"))
        if handler is None:
            return make_nack(self.node_id, ErrorCode.UNKNOWN_OP, self.log.last_seq())
        try:
            return handler(msg)
        except Exception:
            logger.exception(
                "%s: lỗi khi xử lý %s từ %s",
                self.node_id,
                msg.get("type"),
                msg.get("sender"),
            )
            return make_nack(
                self.node_id, ErrorCode.INTERNAL_ERROR, self.log.last_seq()
            )

    def on_applied(self, callback):
        """Đăng ký callback(record) -> dict | None, gọi sau khi S1 áp dụng một cập nhật.

        Dict trả về được gộp vào result của REPLY, ví dụ {"replicated_to_s2": False}.
        """
        self._applied_callbacks.append(callback)

    def notify_applied(self, record):
        """S1 (primary.py) gọi ngay sau apply_update thành công. Trả về dict đã gộp.

        Cập nhật đã ghi xong nên callback lỗi không được làm hỏng REPLY: lỗi được
        ghi log và bỏ qua, các callback khác vẫn chạy.
        Lưu ý cho primary.py: REPLY lưu trong processed_requests được tạo trước khi
        gọi hàm này, nên chưa có các trường callback thêm vào. Khi trả lại REPLY cũ
        cho client gửi lại, tính lại replicated_to_s2 = get_progress("S2") >= seq.
        """
        merged = {}
        for callback in self._applied_callbacks:
            try:
                extra = callback(record)
            except Exception:
                logger.exception(
                    "%s: callback on_applied lỗi ở seq=%s",
                    self.node_id,
                    record.get("seq"),
                )
                continue
            if extra:
                merged.update(extra)
        return merged

    def batch_n(self):
        """N đang dùng: giá trị đổi bằng CONFIG_SET nếu có, không thì từ config."""
        value = self.log.get_runtime_config()["batch_n"]
        return value if value is not None else self.cfg["batch_n"]

    def period_t_seconds(self):
        """T đang dùng: giá trị đổi bằng CONFIG_SET nếu có, không thì từ config."""
        value = self.log.get_runtime_config()["period_t_seconds"]
        return value if value is not None else self.cfg["period_t_seconds"]
