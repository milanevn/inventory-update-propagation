-- PostgreSQL per node. Application-level replication only; no PG publication/subscription.
CREATE TABLE products (
  product_code VARCHAR(64) PRIMARY KEY,
  product_name VARCHAR(200) NOT NULL,
  unit VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE stock_in (
  voucher_no VARCHAR(64) PRIMARY KEY,
  product_code VARCHAR(64) NOT NULL REFERENCES products(product_code),
  quantity BIGINT NOT NULL CHECK (quantity > 0),
  note TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE stock_out (
  voucher_no VARCHAR(64) PRIMARY KEY,
  product_code VARCHAR(64) NOT NULL REFERENCES products(product_code),
  quantity BIGINT NOT NULL CHECK (quantity > 0),
  note TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE inventory (
  product_code VARCHAR(64) PRIMARY KEY REFERENCES products(product_code),
  quantity BIGINT NOT NULL DEFAULT 0 CHECK (quantity >= 0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Node-local gate for ordered commit. SELECT ... FOR UPDATE before issuing seq.
CREATE TABLE node_state (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
  epoch BIGINT NOT NULL DEFAULT 1 CHECK (epoch >= 1),
  last_issued_seq BIGINT NOT NULL DEFAULT 0 CHECK (last_issued_seq >= 0),
  last_applied_seq BIGINT NOT NULL DEFAULT 0 CHECK (last_applied_seq >= 0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO node_state(singleton, epoch, last_issued_seq, last_applied_seq) VALUES(TRUE, 1, 0, 0);
CREATE TABLE operation_log (
  event_id UUID PRIMARY KEY,
  epoch BIGINT NOT NULL CHECK (epoch >= 1),
  seq BIGINT NOT NULL CHECK (seq >= 1),
  operation_type VARCHAR(32) NOT NULL CHECK (operation_type IN ('CREATE_PRODUCT', 'STOCK_IN', 'STOCK_OUT')),
  payload JSONB NOT NULL,
  event_hash VARCHAR(128),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(epoch, seq)
);
CREATE TABLE outbox (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id UUID NOT NULL REFERENCES operation_log(event_id),
  destination_node VARCHAR(8) NOT NULL,
  eligible_at TIMESTAMPTZ, -- S3: set by committed-event scheduler, never before commit+n
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','IN_FLIGHT','ACKED')),
  retry_count INT NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
  next_retry_at TIMESTAMPTZ,
  last_error TEXT,
  UNIQUE(event_id,destination_node)
);
CREATE INDEX outbox_pending_idx ON outbox(destination_node, status, eligible_at, id);
CREATE TABLE replication_progress (
  peer_id VARCHAR(8) PRIMARY KEY,
  epoch BIGINT NOT NULL DEFAULT 1 CHECK (epoch >= 1),
  last_applied_seq BIGINT NOT NULL DEFAULT 0 CHECK (last_applied_seq >= 0),
  last_acked_seq BIGINT NOT NULL DEFAULT 0 CHECK (last_acked_seq >= 0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE processed_requests (
  request_id UUID PRIMARY KEY,
  event_id UUID NOT NULL UNIQUE REFERENCES operation_log(event_id),
  response_payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE replication_policy (
  route_id VARCHAR(20) PRIMARY KEY,
  policy_type VARCHAR(20) NOT NULL CHECK (policy_type IN ('IMMEDIATE','DELAY_SECONDS','BATCH_COUNT')),
  policy_value INT NOT NULL CHECK (policy_value >= 0),
  config_version BIGINT NOT NULL DEFAULT 1 CHECK (config_version >= 1),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO replication_policy(route_id,policy_type,policy_value) VALUES
 ('S1_TO_S2','IMMEDIATE',0),('S1_TO_S3','DELAY_SECONDS',5),('S2_TO_S4','BATCH_COUNT',3);
CREATE TABLE snapshot_metadata (
  snapshot_id UUID PRIMARY KEY,
  source_node VARCHAR(8) NOT NULL,
  epoch BIGINT NOT NULL,
  last_included_seq BIGINT NOT NULL,
  checksum_sha256 VARCHAR(64) NOT NULL,
  storage_location TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
