"""Bản giả để test khi chưa có transport.py, db.py, client/net.py. Phụ trách: TV1 + TV2.

- FakeNetwork + FakeTransport: "mạng" trong RAM nối các node, giả lập mất gói,
  mất trả lời, gói trùng, máy chết. Gửi là đồng bộ, không có socket, không có thread.
  Hành xử như Transport thật: gửi lại cùng msg_id, bên nhận nhớ reply theo msg_id.
- FakeLog: UpdateLog trong RAM, áp dụng đúng quy tắc seq như bản thật.
- FakeNet: ClientNet trả lời đóng sẵn, để làm giao diện khi chưa có máy chủ.
- FakeClock: đồng hồ chỉnh tay, để test chu kỳ T, heartbeat mà không phải sleep.

Mọi thông điệp đi qua bản giả đều được encode/decode và validate_data, nên gửi
thiếu trường hay dữ liệu không phải JSON sẽ báo lỗi ngay khi test.

Ví dụ:
    net = FakeNetwork()
    s1, s2 = net.transport("S1"), net.transport("S2")
    s2.serve(lambda msg: make_ack("S2", 0))
    reply = s1.request("S2", make_heartbeat("S1", 0))
    net.set_down("S2")          # S2 chết: request tới S2 ném NodeUnreachable
    net.drop("S2", 2)           # 2 lần gửi tới S2 tiếp theo bị mất, lần 3 mới tới
    net.drop_reply("S2")        # S2 xử lý xong nhưng trả lời bị mất; lần gửi lại
                                # nhận reply cũ, handler không chạy lần hai
"""

import copy
import threading

from common.interfaces import (
    ClientNet,
    LogTruncated,
    NodeUnreachable,
    SeqGapError,
    Transport,
    UpdateLog,
    make_nack,
    make_reply,
    validate_data,
    validate_record,
)
from common.protocol import (
    ErrorCode,
    MsgType,
    Op,
    decode,
    encode,
    make_message,
    new_msg_id,
)

# ---------------------------------------------------------------------------
# Mạng giả
# ---------------------------------------------------------------------------


def _wire(message):
    """Đi qua "dây": kiểm tra data, encode, decode, như khi gửi qua socket thật."""
    raw = encode(message)
    decoded = decode(raw)
    validate_data(decoded)
    return decoded


class FakeNetwork:
    """Nối các FakeTransport trong cùng một tiến trình."""

    def __init__(self, max_retries=3):
        self.max_retries = max_retries
        self.sent = []  # (sender, target, message) theo thứ tự, gồm cả lần gửi lại
        self._handlers = {}
        self._replies = (
            {}
        )  # target -> {msg_id: reply}, như bộ nhớ chống trùng của Transport
        self._down = set()
        self._drop = {}
        self._drop_reply = {}
        self._duplicate = {}
        self._lock = threading.RLock()

    def transport(self, node_id):
        return FakeTransport(self, node_id)

    def set_down(self, node_id, down=True):
        """Giả lập máy chết (down=True) hoặc sống lại (down=False).

        Máy sống lại mất bộ nhớ chống trùng, giống máy thật khởi động lại.
        """
        with self._lock:
            if down:
                self._down.add(node_id)
                self._replies.pop(node_id, None)
            else:
                self._down.discard(node_id)

    def is_down(self, node_id):
        with self._lock:
            return node_id in self._down

    def drop(self, target, count=1):
        """count lần gửi tiếp theo tới target bị mất trên đường đi (handler không chạy)."""
        with self._lock:
            self._drop[target] = self._drop.get(target, 0) + count

    def drop_reply(self, target, count=1):
        """count lần tiếp theo target xử lý xong nhưng trả lời bị mất trên đường về.

        Bên gửi hết timeout và gửi lại cùng msg_id; target trả lại reply cũ, không
        gọi handler lần nữa. Đây là trường hợp "S2 đã áp dụng nhưng S1 không biết".
        """
        with self._lock:
            self._drop_reply[target] = self._drop_reply.get(target, 0) + count

    def duplicate(self, target, count=1):
        """count thông điệp tiếp theo tới target được giao thêm một bản với msg_id mới.

        Giả lập cùng nội dung tới hai lần qua hai thông điệp khác nhau (ví dụ UPDATE
        rồi SYNC_RESPONSE chứa lại cùng seq). Bộ chống trùng theo msg_id không chặn
        được, nên handler phải tự xử lý trùng (apply_update bỏ qua seq cũ).
        """
        with self._lock:
            self._duplicate[target] = self._duplicate.get(target, 0) + count

    def messages_to(self, target, msg_type=None):
        """Các thông điệp đã gửi tới target (lọc theo loại nếu có)."""
        with self._lock:
            return [
                copy.deepcopy(m)
                for _, t, m in self.sent
                if t == target and (msg_type is None or m["type"] == msg_type)
            ]

    def _register(self, node_id, handler):
        with self._lock:
            self._handlers[node_id] = handler

    def _unregister(self, node_id):
        with self._lock:
            self._handlers.pop(node_id, None)
            self._replies.pop(node_id, None)

    def _attempt(self, sender, target, message):
        """Một lần gửi. Trả (True, reply) nếu có trả lời, (False, None) nếu mất."""
        with self._lock:
            self.sent.append((sender, target, copy.deepcopy(message)))
            handler = self._handlers.get(target)
            if handler is None or target in self._down:
                return False, None
            if self._drop.get(target, 0) > 0:
                self._drop[target] -= 1
                return False, None
            cache = self._replies.setdefault(target, {})
            reply = cache.get(message["msg_id"])
            twice = False
            if reply is None:
                twice = self._duplicate.get(target, 0) > 0
                if twice:
                    self._duplicate[target] -= 1
        if reply is None:
            # Gọi handler ngoài khóa để handler được phép gửi tiếp cho máy khác.
            reply = _wire(handler(copy.deepcopy(message)))
            if twice:
                handler(dict(copy.deepcopy(message), msg_id=new_msg_id()))
            with self._lock:
                self._replies.setdefault(target, {})[message["msg_id"]] = reply
        with self._lock:
            if self._drop_reply.get(target, 0) > 0:
                self._drop_reply[target] -= 1
                return False, None
        return True, copy.deepcopy(reply)


class FakeTransport(Transport):
    """Transport giả. Thông điệp đi qua encode/decode thật nên dữ liệu không phải JSON
    hoặc vượt 1 MB sẽ báo lỗi giống bản thật."""

    def __init__(self, network, node_id):
        self.network = network
        self.node_id = node_id

    def serve(self, handler):
        self.network._register(self.node_id, handler)

    def request(self, target, message):
        message = _wire(message)
        for _ in range(self.network.max_retries):
            delivered, reply = self.network._attempt(self.node_id, target, message)
            if delivered:
                return reply
        raise NodeUnreachable(target)

    def close(self):
        self.network._unregister(self.node_id)


# ---------------------------------------------------------------------------
# Nhật ký giả
# ---------------------------------------------------------------------------


class FakeLog(UpdateLog):
    """UpdateLog trong RAM. Hành vi phải giống server/db.py; tests/test_fakes.py kiểm tra."""

    def __init__(self):
        self._lock = threading.RLock()
        self._records = {}  # seq -> bản ghi
        self._last_seq = 0
        self._first_seq = 1
        self._checkpoint_seq = 0
        self._runtime = {"batch_n": None, "period_t_seconds": None}
        self._progress = {}
        self._processed = {}
        self._products = {}  # product_code -> dict
        self._inventory = {}  # product_code -> quantity
        self._stock_in = []
        self._stock_out = []

    # --- Nhật ký và áp dụng cập nhật ---

    def last_seq(self):
        with self._lock:
            return self._last_seq

    def first_seq(self):
        with self._lock:
            return self._first_seq

    def read_from(self, from_seq, limit=None):
        with self._lock:
            if from_seq < self._first_seq:
                raise LogTruncated(from_seq, self._first_seq)
            seqs = sorted(s for s in self._records if s >= from_seq)
            if limit is not None:
                seqs = seqs[:limit]
            return [copy.deepcopy(self._records[s]) for s in seqs]

    def apply_update(self, record, processed=None):
        validate_record(record)
        with self._lock:
            seq = record["seq"]
            if seq <= self._last_seq:
                return False
            if seq > self._last_seq + 1:
                raise SeqGapError(self._last_seq, seq)
            self._apply_business(record)  # ném ValueError trước khi đổi gì
            self._records[seq] = copy.deepcopy(record)
            self._last_seq = seq
            if processed is not None:
                msg_id, reply = processed
                self._processed[msg_id] = copy.deepcopy(reply)
            return True

    def _apply_business(self, record):
        op, data, seq = record["op"], record["data"], record["seq"]
        code = data["product_code"]
        if op == Op.ADD_PRODUCT:
            if code in self._products:
                raise ValueError(f"Mã hàng đã có: {code}")
            self._products[code] = {
                "product_code": code,
                "product_name": data["product_name"],
                "unit": data["unit"],
                "created_at": record["created_at"],
                "seq": seq,
            }
            self._inventory[code] = 0
            return
        if code not in self._products:
            raise ValueError(f"Không có mã hàng: {code}")
        delta = data["quantity"] if op == Op.STOCK_IN else -data["quantity"]
        if self._inventory[code] + delta < 0:
            raise ValueError(f"Tồn âm: {code}")
        self._inventory[code] += delta
        row = {
            "voucher_no": data["voucher_no"],
            "product_code": code,
            "quantity": data["quantity"],
            "unit_price": data["unit_price"],
            "note": data["note"],
            "created_at": record["created_at"],
            "seq": seq,
        }
        (self._stock_in if op == Op.STOCK_IN else self._stock_out).append(row)

    def get_processed(self, msg_id):
        with self._lock:
            reply = self._processed.get(msg_id)
            return copy.deepcopy(reply) if reply is not None else None

    # --- Đọc dữ liệu kho ---

    def _item(self, code):
        p = self._products[code]
        return {
            "product_code": code,
            "product_name": p["product_name"],
            "unit": p["unit"],
            "quantity": self._inventory[code],
        }

    def get_product(self, product_code):
        with self._lock:
            if product_code not in self._products:
                return None
            return self._item(product_code)

    def stock(self, product_code=None):
        with self._lock:
            codes = sorted(self._products)
            if product_code is not None:
                codes = [c for c in codes if c == product_code]
            return [self._item(c) for c in codes]

    # --- Tiến độ lan truyền ---

    def get_progress(self, target):
        with self._lock:
            return self._progress.get(target, 0)

    def set_progress(self, target, last_acked_seq):
        with self._lock:
            self._progress[target] = last_acked_seq

    # --- Trạng thái máy ---

    def get_runtime_config(self):
        with self._lock:
            return dict(self._runtime)

    def set_runtime_config(self, batch_n=None, period_t_seconds=None):
        if batch_n is not None and (not isinstance(batch_n, int) or batch_n < 1):
            raise ValueError("batch_n phải là số nguyên >= 1")
        if period_t_seconds is not None and period_t_seconds <= 0:
            raise ValueError("period_t_seconds phải > 0")
        with self._lock:
            if batch_n is not None:
                self._runtime["batch_n"] = batch_n
            if period_t_seconds is not None:
                self._runtime["period_t_seconds"] = period_t_seconds

    def checkpoint_seq(self):
        with self._lock:
            return self._checkpoint_seq

    def set_checkpoint_seq(self, seq):
        with self._lock:
            self._checkpoint_seq = seq

    def truncate_log(self, before_seq, targets):
        with self._lock:
            limit = min(before_seq, self._last_seq + 1)
            for target in targets:
                limit = min(limit, self._progress.get(target, 0) + 1)
            if limit > self._first_seq:
                for s in [s for s in self._records if s < limit]:
                    del self._records[s]
                self._first_seq = limit
            return self._first_seq

    # --- Bản chụp ---

    def export_snapshot(self):
        with self._lock:
            products = [
                {
                    k: p[k]
                    for k in (
                        "product_code",
                        "product_name",
                        "unit",
                        "created_at",
                        "seq",
                    )
                }
                for _, p in sorted(self._products.items())
            ]
            inventory = [
                {"product_code": c, "quantity": q, "seq": self._latest_seq(c)}
                for c, q in sorted(self._inventory.items())
            ]
            return copy.deepcopy(
                {
                    "last_seq": self._last_seq,
                    "tables": {
                        "products": products,
                        "stock_in": self._stock_in,
                        "stock_out": self._stock_out,
                        "inventory": inventory,
                    },
                }
            )

    def _latest_seq(self, code):
        seqs = [self._products[code]["seq"]]
        seqs += [
            r["seq"]
            for r in self._stock_in + self._stock_out
            if r["product_code"] == code
        ]
        return max(seqs)

    def import_snapshot(self, snapshot):
        tables = snapshot["tables"]
        with self._lock:
            self._products = {p["product_code"]: dict(p) for p in tables["products"]}
            self._inventory = {
                i["product_code"]: i["quantity"] for i in tables["inventory"]
            }
            self._stock_in = [dict(r) for r in tables["stock_in"]]
            self._stock_out = [dict(r) for r in tables["stock_out"]]
            self._records = {}
            self._last_seq = snapshot["last_seq"]
            self._first_seq = self._last_seq + 1


# ---------------------------------------------------------------------------
# Client giả
# ---------------------------------------------------------------------------


class FakeNet(ClientNet):
    """ClientNet giả cho các tab giao diện.

    responder(target, message) -> reply. Không truyền thì dùng demo_responder,
    trả dữ liệu mẫu cho GET_STOCK, STATUS_REQUEST, CONFIG_SET và báo thành công
    cho thao tác ghi. Đặt down={"S3"} để giả lập máy S3 không trả lời.
    Thông điệp gửi đi thiếu trường data thì ném ProtocolError ngay, trước khi gửi.
    """

    def __init__(self, responder=None, down=None):
        self.responder = responder or demo_responder
        self.down = set(down or ())
        self.sent = []

    def request(self, target, message):
        message = _wire(message)
        self.sent.append((target, copy.deepcopy(message)))
        if target in self.down:
            raise NodeUnreachable(target)
        return _wire(self.responder(target, message))


DEMO_ITEMS = [
    {"product_code": "SP001", "product_name": "Bút bi", "unit": "cây", "quantity": 120},
    {
        "product_code": "SP002",
        "product_name": "Vở 96 trang",
        "unit": "quyển",
        "quantity": 45,
    },
    {"product_code": "SP003", "product_name": "Thước kẻ", "unit": "cây", "quantity": 0},
]

DEMO_LAST_SEQ = {"S1": 12, "S2": 12, "S3": 10, "S4": 10}


def demo_responder(target, message):
    """Trả lời mẫu, đủ để các tab hiển thị được."""
    last_seq = DEMO_LAST_SEQ.get(target, 0)
    msg_type, op, data = message["type"], message["op"], message["data"]

    if msg_type == MsgType.STATUS_REQUEST:
        return make_message(
            MsgType.STATUS_RESPONSE,
            target,
            data={
                "node_id": target,
                "is_primary": target == "S1",
                "last_seq": last_seq,
                "checkpoint_seq": 8,
                "batch_n": 5,
                "period_t_seconds": 10,
                "peers": {
                    n: {"alive": True, "last_seen": "2026-10-22T09:00:00"}
                    for n in DEMO_LAST_SEQ
                    if n != target
                },
                "progress": {"S1": {"S2": 12, "S3": 10}, "S2": {"S4": 10}}.get(
                    target, {}
                ),
            },
        )

    if msg_type == MsgType.CONFIG_SET:
        return make_reply(
            target,
            True,
            result={
                "batch_n": data.get("batch_n") or 5,
                "period_t_seconds": data.get("period_t_seconds") or 10,
            },
            last_seq=last_seq,
        )

    if msg_type == MsgType.ADMIN:
        return make_reply(
            target,
            True,
            result={"message": f"Đã thực hiện {op} trên {target}"},
            last_seq=last_seq,
        )

    if msg_type == MsgType.REQUEST and op == Op.GET_STOCK:
        code = data.get("product_code")
        items = [i for i in DEMO_ITEMS if code is None or i["product_code"] == code]
        return make_reply(target, True, result={"items": items}, last_seq=last_seq)

    if msg_type == MsgType.REQUEST:
        if target != "S1":
            return make_nack(target, ErrorCode.NOT_PRIMARY, last_seq)
        seq = last_seq + 1
        result = {"seq": seq, "product_code": data.get("product_code")}
        if op in (Op.STOCK_IN, Op.STOCK_OUT):
            prefix = "PN-" if op == Op.STOCK_IN else "PX-"
            result.update(
                {
                    "voucher_no": f"{prefix}{seq:06d}",
                    "quantity_after": 100,
                    "replicated_to_s2": True,
                }
            )
        return make_reply(target, True, result=result, last_seq=seq)

    return make_nack(target, ErrorCode.UNKNOWN_OP, last_seq)


# ---------------------------------------------------------------------------
# Đồng hồ giả
# ---------------------------------------------------------------------------


class FakeClock:
    """Đồng hồ chỉnh tay. Truyền vào chỗ cần thời gian hiện tại (NodeContext(clock=...))."""

    def __init__(self, start=0.0):
        self.now = start

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds
        return self.now
