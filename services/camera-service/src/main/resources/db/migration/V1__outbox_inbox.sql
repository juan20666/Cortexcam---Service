CREATE TABLE outbox_event (
  id            UUID PRIMARY KEY,
  topic         VARCHAR(160) NOT NULL,
  partition_key VARCHAR(80)  NOT NULL,
  event_type    VARCHAR(160) NOT NULL,
  payload       TEXT         NOT NULL,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  published_at  TIMESTAMPTZ
);
CREATE INDEX ix_outbox_pending ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_message (
  message_id   VARCHAR(160) NOT NULL,
  consumer     VARCHAR(80)  NOT NULL,
  processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, consumer)
);