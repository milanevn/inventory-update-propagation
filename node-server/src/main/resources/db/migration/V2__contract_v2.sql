-- Contract v2 schema (docs/thiet-ke.md, section 3 "Mo hinh du lieu").

-- node_state: role and node status are durable. role stays NULL until the node's first start,
-- when it is initialised from NODE_ROLE; after that node_state is the source of truth.
ALTER TABLE node_state
  ADD COLUMN role VARCHAR(16) CHECK (role IN ('PRIMARY', 'REPLICA')),
  ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'READY'
    CHECK (status IN ('READY', 'QUARANTINED', 'RESTORING'));

-- operation_log: system events, originating request and payload version; event_hash is dropped.
ALTER TABLE operation_log DROP CONSTRAINT operation_log_operation_type_check;
ALTER TABLE operation_log
  ADD CONSTRAINT operation_log_operation_type_check CHECK (operation_type IN
    ('CREATE_PRODUCT', 'STOCK_IN', 'STOCK_OUT', 'POLICY_CHANGE', 'EPOCH_START')),
  ADD COLUMN request_id UUID, -- NULL for EPOCH_START
  ADD COLUMN schema_version INT NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
  DROP COLUMN event_hash;
-- seq is contiguous across epochs, so it is unique on every node (an old primary drops its lost
-- events before replaying the new history).
CREATE UNIQUE INDEX operation_log_seq_key ON operation_log(seq);

-- outbox: delay actually applied on the S3 route (NULL for other destinations).
ALTER TABLE outbox
  ADD COLUMN delay_seconds INT CHECK (delay_seconds BETWEEN 0 AND 3600);

-- processed_requests: rejected commands store their result without an event.
-- request_payload is compared on retry: same content returns the stored result, different
-- content is INVALID_ARGUMENT.
ALTER TABLE processed_requests
  ALTER COLUMN event_id DROP NOT NULL,
  ADD COLUMN request_payload JSONB NOT NULL;

-- S4 batches: fixed membership across retries.
CREATE TABLE replication_batch (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  destination_node VARCHAR(8) NOT NULL,
  first_seq BIGINT NOT NULL CHECK (first_seq >= 1),
  last_seq BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_FLIGHT', 'ACKED')),
  retry_count INT NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
  next_retry_at TIMESTAMPTZ,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (last_seq >= first_seq),
  UNIQUE (destination_node, first_seq)
);

-- Replication policy: one row, two independent parameters, one configuration version.
DROP TABLE replication_policy;
CREATE TABLE replication_policy (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
  delay_seconds INT NOT NULL CHECK (delay_seconds BETWEEN 0 AND 3600),
  batch_size INT NOT NULL CHECK (batch_size BETWEEN 1 AND 100),
  config_version BIGINT NOT NULL DEFAULT 1 CHECK (config_version >= 1),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO replication_policy(singleton, delay_seconds, batch_size) VALUES (TRUE, 5, 3);

-- Built from EPOCH_START events. Epoch 1 has no row: it starts at seq 1 and the S4 source is S2.
CREATE TABLE epoch_history (
  epoch BIGINT PRIMARY KEY CHECK (epoch >= 2),
  start_seq BIGINT NOT NULL UNIQUE CHECK (start_seq >= 1),
  node_id VARCHAR(8) NOT NULL,
  s4_source_node VARCHAR(8) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Recovery replays the operation log; snapshots are out of scope.
DROP TABLE snapshot_metadata;
