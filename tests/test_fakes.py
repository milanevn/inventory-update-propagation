"""Kiểm tra bản giả trong common/fakes.py và các hàm dùng chung trong common/interfaces.py.

UpdateLogContract là bộ test hành vi của UpdateLog. Khi có server/db.py, TV2 tạo
lớp con của UpdateLogContract với make_log() trả về UpdateLog thật để chắc chắn
FakeLog và bản thật hành xử giống nhau.
"""

import unittest

from common import config
from common.fakes import FakeClock, FakeLog, FakeNet, FakeNetwork
from common.interfaces import (
    LogTruncated,
    NodeContext,
    NodeUnreachable,
    SeqGapError,
    handle_updates,
    make_ack,
    validate_record,
)
from common.protocol import ErrorCode, MsgType, Op, make_message, voucher_no

T0 = "2026-10-22T09:00:00"


def add_product(seq, code="SP001", name="Bút bi", unit="cây"):
    return {"seq": seq, "op": Op.ADD_PRODUCT, "created_at": T0,
            "data": {"product_code": code, "product_name": name, "unit": unit}}


def stock(op, seq, quantity, code="SP001"):
    return {"seq": seq, "op": op, "created_at": T0,
            "data": {"voucher_no": voucher_no(op, seq), "product_code": code,
                     "quantity": quantity, "unit_price": None, "note": None}}


class UpdateLogContract:
    """Lớp con phải định nghĩa make_log() và kế thừa unittest.TestCase."""

    def make_log(self):
        raise NotImplementedError

    def setUp(self):
        self.log = self.make_log()

    def apply_basic(self):
        self.log.apply_update(add_product(1))
        self.log.apply_update(stock(Op.STOCK_IN, 2, 10))
        self.log.apply_update(stock(Op.STOCK_OUT, 3, 4))

    def test_empty_log(self):
        self.assertEqual(self.log.last_seq(), 0)
        self.assertEqual(self.log.first_seq(), 1)
        self.assertEqual(self.log.read_from(1), [])
        self.assertEqual(self.log.stock(), [])

    def test_next_seq_is_applied(self):
        self.assertTrue(self.log.apply_update(add_product(1)))
        self.assertEqual(self.log.last_seq(), 1)

    def test_duplicate_seq_is_skipped(self):
        self.apply_basic()
        self.assertFalse(self.log.apply_update(stock(Op.STOCK_IN, 2, 10)))
        self.assertEqual(self.log.get_product("SP001")["quantity"], 6)

    def test_seq_gap_raises_and_changes_nothing(self):
        self.log.apply_update(add_product(1))
        with self.assertRaises(SeqGapError) as cm:
            self.log.apply_update(stock(Op.STOCK_IN, 3, 10))
        self.assertEqual(cm.exception.last_seq, 1)
        self.assertEqual(self.log.last_seq(), 1)
        self.assertEqual(self.log.get_product("SP001")["quantity"], 0)

    def test_business_effects(self):
        self.apply_basic()
        self.assertEqual(self.log.stock(), [
            {"product_code": "SP001", "product_name": "Bút bi", "unit": "cây", "quantity": 6},
        ])
        self.assertIsNone(self.log.get_product("SP999"))
        self.assertEqual(self.log.stock("SP999"), [])

    def test_negative_stock_rejected_without_change(self):
        self.apply_basic()
        with self.assertRaises(ValueError):
            self.log.apply_update(stock(Op.STOCK_OUT, 4, 7))
        self.assertEqual(self.log.last_seq(), 3)
        self.assertEqual(self.log.get_product("SP001")["quantity"], 6)

    def test_unknown_product_rejected(self):
        with self.assertRaises(ValueError):
            self.log.apply_update(stock(Op.STOCK_IN, 1, 5, code="SP404"))
        self.assertEqual(self.log.last_seq(), 0)

    def test_read_from_order_and_limit(self):
        self.apply_basic()
        self.assertEqual([r["seq"] for r in self.log.read_from(2)], [2, 3])
        self.assertEqual([r["seq"] for r in self.log.read_from(1, limit=2)], [1, 2])
        self.assertEqual(self.log.read_from(1)[0], add_product(1))

    def test_processed_reply_saved_with_update(self):
        reply = {"type": "REPLY", "data": {"ok": True}}
        self.log.apply_update(add_product(1), processed=("abc", reply))
        self.assertEqual(self.log.get_processed("abc"), reply)
        self.assertIsNone(self.log.get_processed("khac"))

    def test_progress(self):
        self.assertEqual(self.log.get_progress("S2"), 0)
        self.log.set_progress("S2", 5)
        self.assertEqual(self.log.get_progress("S2"), 5)

    def test_runtime_config_keeps_unset_values(self):
        self.assertEqual(self.log.get_runtime_config(), {"batch_n": None, "period_t_seconds": None})
        self.log.set_runtime_config(batch_n=3)
        self.log.set_runtime_config(period_t_seconds=2.5)
        self.assertEqual(self.log.get_runtime_config(), {"batch_n": 3, "period_t_seconds": 2.5})
        with self.assertRaises(ValueError):
            self.log.set_runtime_config(batch_n=0)

    def test_truncate_keeps_unacked_records(self):
        self.apply_basic()
        self.log.set_progress("S2", 3)
        self.log.set_progress("S3", 1)
        self.log.set_checkpoint_seq(3)
        self.assertEqual(self.log.truncate_log(4), 2)
        self.assertEqual(self.log.checkpoint_seq(), 3)
        self.assertEqual([r["seq"] for r in self.log.read_from(2)], [2, 3])
        with self.assertRaises(LogTruncated) as cm:
            self.log.read_from(1)
        self.assertEqual(cm.exception.first_seq, 2)

    def test_snapshot_roundtrip(self):
        self.apply_basic()
        snap = self.log.export_snapshot()
        self.assertEqual(snap["last_seq"], 3)
        self.assertEqual(set(snap["tables"]), {"products", "stock_in", "stock_out", "inventory"})
        other = self.make_log()
        other.import_snapshot(snap)
        self.assertEqual(other.last_seq(), 3)
        self.assertEqual(other.first_seq(), 4)
        self.assertEqual(other.stock(), self.log.stock())
        self.assertEqual(other.export_snapshot(), snap)
        self.assertTrue(other.apply_update(stock(Op.STOCK_IN, 4, 1)))


class FakeLogTest(UpdateLogContract, unittest.TestCase):
    def make_log(self):
        return FakeLog()


class ValidateRecordTest(unittest.TestCase):
    def test_valid_records(self):
        validate_record(add_product(1))
        validate_record(stock(Op.STOCK_OUT, 2, 1))

    def test_invalid_records(self):
        bad = [
            {"seq": 1, "op": Op.ADD_PRODUCT, "data": {}},                 # thiếu created_at
            dict(add_product(1), seq=0),
            dict(add_product(1), op=Op.GET_STOCK),
            stock(Op.STOCK_IN, 1, 0),
            stock(Op.STOCK_IN, 1, True),
        ]
        for record in bad:
            with self.subTest(record=record), self.assertRaises(ValueError):
                validate_record(record)


class FakeTransportTest(unittest.TestCase):
    def setUp(self):
        self.net = FakeNetwork(max_retries=3)
        self.s1 = self.net.transport("S1")
        self.s2 = self.net.transport("S2")
        self.received = []

        def handler(msg):
            self.received.append(msg)
            return make_ack("S2", 7)

        self.s2.serve(handler)
        self.msg = make_message(MsgType.HEARTBEAT, "S1", data={"last_seq": 0})

    def test_deliver_and_reply(self):
        reply = self.s1.request("S2", self.msg)
        self.assertEqual(reply["type"], MsgType.ACK)
        self.assertEqual(reply["data"]["last_seq"], 7)
        self.assertEqual(self.received, [self.msg])

    def test_down_node_unreachable(self):
        self.net.set_down("S2")
        with self.assertRaises(NodeUnreachable):
            self.s1.request("S2", self.msg)
        self.assertEqual(len(self.net.messages_to("S2")), 3)
        self.net.set_down("S2", False)
        self.s1.request("S2", self.msg)

    def test_drop_then_retry_with_same_msg_id(self):
        self.net.drop("S2", 2)
        self.s1.request("S2", self.msg)
        sent = self.net.messages_to("S2")
        self.assertEqual(len(sent), 3)
        self.assertEqual({m["msg_id"] for m in sent}, {self.msg["msg_id"]})
        self.assertEqual(len(self.received), 1)

    def test_drop_more_than_retries(self):
        self.net.drop("S2", 3)
        with self.assertRaises(NodeUnreachable):
            self.s1.request("S2", self.msg)

    def test_duplicate_delivery(self):
        self.net.duplicate("S2")
        self.s1.request("S2", self.msg)
        self.assertEqual(len(self.received), 2)

    def test_not_serving_or_closed(self):
        with self.assertRaises(NodeUnreachable):
            self.s2.request("S3", self.msg)
        self.s2.close()
        self.s2.close()
        with self.assertRaises(NodeUnreachable):
            self.s1.request("S2", self.msg)

    def test_non_json_data_rejected(self):
        with self.assertRaises(TypeError):
            self.s1.request("S2", make_message(MsgType.HEARTBEAT, "S1", data={"x": object()}))


class HandleUpdatesTest(unittest.TestCase):
    def test_ack_and_skip_duplicates(self):
        log = FakeLog()
        reply = handle_updates(log, "S2", [add_product(1), stock(Op.STOCK_IN, 2, 5)])
        self.assertEqual((reply["type"], reply["data"]["last_seq"]), (MsgType.ACK, 2))
        reply = handle_updates(log, "S2", [stock(Op.STOCK_IN, 2, 5), stock(Op.STOCK_IN, 3, 1)])
        self.assertEqual(reply["data"]["last_seq"], 3)
        self.assertEqual(log.get_product("SP001")["quantity"], 6)

    def test_gap_returns_nack_with_last_seq(self):
        log = FakeLog()
        reply = handle_updates(log, "S4", [add_product(1), stock(Op.STOCK_IN, 3, 5)])
        self.assertEqual(reply["type"], MsgType.NACK)
        self.assertEqual(reply["data"]["code"], ErrorCode.SEQ_GAP)
        self.assertEqual(reply["data"]["last_seq"], 1)


class NodeContextTest(unittest.TestCase):
    def setUp(self):
        cfg = config.load()
        self.log = FakeLog()
        self.clock = FakeClock(100.0)
        self.ctx = NodeContext("S1", cfg, FakeNetwork().transport("S1"), self.log, clock=self.clock)

    def test_dispatch_by_type(self):
        self.ctx.on(MsgType.HEARTBEAT, lambda msg: make_ack("S1", 0))
        reply = self.ctx.dispatch(make_message(MsgType.HEARTBEAT, "S2"))
        self.assertEqual(reply["type"], MsgType.ACK)
        with self.assertRaises(ValueError):
            self.ctx.on(MsgType.HEARTBEAT, lambda msg: None)

    def test_unknown_type_and_handler_error(self):
        reply = self.ctx.dispatch(make_message(MsgType.UPDATE, "S2"))
        self.assertEqual(reply["data"]["code"], ErrorCode.UNKNOWN_OP)

        def broken(msg):
            raise RuntimeError("lỗi")

        self.ctx.on(MsgType.UPDATE, broken)
        reply = self.ctx.dispatch(make_message(MsgType.UPDATE, "S2"))
        self.assertEqual(reply["data"]["code"], ErrorCode.INTERNAL_ERROR)

    def test_notify_applied_merges_results(self):
        self.ctx.on_applied(lambda record: {"replicated_to_s2": False})
        self.ctx.on_applied(lambda record: None)
        self.assertEqual(self.ctx.notify_applied(add_product(1)), {"replicated_to_s2": False})

    def test_runtime_config_overrides_config(self):
        self.assertTrue(self.ctx.is_primary)
        self.assertEqual(self.ctx.batch_n(), self.ctx.cfg["batch_n"])
        self.log.set_runtime_config(batch_n=2)
        self.assertEqual(self.ctx.batch_n(), 2)
        self.assertEqual(self.ctx.period_t_seconds(), self.ctx.cfg["period_t_seconds"])

    def test_clock(self):
        self.assertEqual(self.ctx.clock(), 100.0)
        self.clock.advance(2.5)
        self.assertEqual(self.ctx.clock(), 102.5)


class FakeNetTest(unittest.TestCase):
    def test_demo_replies(self):
        net = FakeNet()
        reply = net.request("S3", make_message(MsgType.REQUEST, "CLIENT", op=Op.GET_STOCK,
                                               data={"product_code": None}))
        self.assertTrue(reply["data"]["ok"])
        self.assertEqual(len(reply["data"]["result"]["items"]), 3)

        reply = net.request("S2", make_message(MsgType.REQUEST, "CLIENT", op=Op.STOCK_IN,
                                               data={"product_code": "SP001", "quantity": 1,
                                                     "unit_price": None, "note": None}))
        self.assertEqual(reply["type"], MsgType.NACK)
        self.assertEqual(reply["data"]["code"], ErrorCode.NOT_PRIMARY)

        reply = net.request("S2", make_message(MsgType.STATUS_REQUEST, "CLIENT"))
        self.assertEqual(reply["data"]["node_id"], "S2")

    def test_down_and_custom_responder(self):
        net = FakeNet(responder=lambda target, msg: make_ack(target, 1), down={"S4"})
        with self.assertRaises(NodeUnreachable):
            net.request("S4", make_message(MsgType.STATUS_REQUEST, "CLIENT"))
        self.assertEqual(net.request("S1", make_message(MsgType.STATUS_REQUEST, "CLIENT"))["type"],
                         MsgType.ACK)
        self.assertEqual(len(net.sent), 2)


if __name__ == "__main__":
    unittest.main()
