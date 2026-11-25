-- Per-destination cursor replication (docs/thiet-ke.md, section 7, decision 17).
-- operation_log is the send queue; each destination has one cursor row in replication_progress.

-- outbox is replaced by the per-destination cursor; its index and the V2 delay_seconds column go with it.
DROP TABLE outbox;

-- S4 batch boundaries are computed from operation_log and the S4 cursor, so retries always resend
-- the same batch; no stored batch is needed.
DROP TABLE replication_batch;

-- committed_at: when this node wrote the row, on this node's own clock (primary: command commit;
-- replica: event apply; ReplicationEvent carries no primary timestamp). created_at defaults to the
-- transaction start, so the application sets committed_at explicitly from its Clock (simulated in tests).
-- Existing rows take created_at, the closest value available.
ALTER TABLE operation_log ADD COLUMN committed_at TIMESTAMPTZ;
UPDATE operation_log SET committed_at = created_at;
ALTER TABLE operation_log ALTER COLUMN committed_at SET NOT NULL;

-- One row per destination: cursor last_acked_seq plus retry state. The V1 columns epoch and
-- last_applied_seq are dropped: the receiver's progress comes from its ApplyReply or GetStatus and
-- only moves the cursor, it is not stored separately.
ALTER TABLE replication_progress
  DROP COLUMN epoch,
  DROP COLUMN last_applied_seq,
  ADD COLUMN retry_count INT NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
  ADD COLUMN next_retry_at TIMESTAMPTZ,
  ADD COLUMN last_error TEXT;
