-- Reconcile outbox tables for legacy environments.
-- Ensures outbox_event/outbox_delivery core columns exist with usable defaults.

CREATE TABLE IF NOT EXISTS outbox_event (
  event_id UUID PRIMARY KEY,
  aggregate_type TEXT NOT NULL,
  aggregate_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  payload JSONB NOT NULL,
  topic TEXT NOT NULL,
  partition_key TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS outbox_delivery (
  event_id UUID PRIMARY KEY REFERENCES outbox_event(event_id),
  status TEXT NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_error TEXT,
  next_retry_at TIMESTAMPTZ,
  locked_by TEXT,
  locked_at TIMESTAMPTZ,
  sent_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS event_id UUID;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS aggregate_type TEXT;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS aggregate_id TEXT;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS event_type TEXT;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS payload JSONB;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS topic TEXT;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS partition_key TEXT;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS occurred_at TIMESTAMPTZ;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

ALTER TABLE outbox_delivery ADD COLUMN IF NOT EXISTS event_id UUID;
ALTER TABLE outbox_delivery ADD COLUMN IF NOT EXISTS status TEXT;
ALTER TABLE outbox_delivery ADD COLUMN IF NOT EXISTS attempt_count INT;
ALTER TABLE outbox_delivery ADD COLUMN IF NOT EXISTS last_error TEXT;
ALTER TABLE outbox_delivery ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;
ALTER TABLE outbox_delivery ADD COLUMN IF NOT EXISTS locked_by TEXT;
ALTER TABLE outbox_delivery ADD COLUMN IF NOT EXISTS locked_at TIMESTAMPTZ;
ALTER TABLE outbox_delivery ADD COLUMN IF NOT EXISTS sent_at TIMESTAMPTZ;
ALTER TABLE outbox_delivery ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE outbox_event
SET created_at = COALESCE(created_at, now()),
    occurred_at = COALESCE(occurred_at, created_at, now())
WHERE created_at IS NULL OR occurred_at IS NULL;

UPDATE outbox_delivery
SET attempt_count = COALESCE(attempt_count, 0),
    updated_at = COALESCE(updated_at, now()),
    status = COALESCE(status, 'INIT')
WHERE attempt_count IS NULL OR updated_at IS NULL OR status IS NULL;

ALTER TABLE outbox_event ALTER COLUMN event_id SET NOT NULL;
ALTER TABLE outbox_event ALTER COLUMN aggregate_type SET NOT NULL;
ALTER TABLE outbox_event ALTER COLUMN aggregate_id SET NOT NULL;
ALTER TABLE outbox_event ALTER COLUMN event_type SET NOT NULL;
ALTER TABLE outbox_event ALTER COLUMN topic SET NOT NULL;
ALTER TABLE outbox_event ALTER COLUMN partition_key SET NOT NULL;
ALTER TABLE outbox_event ALTER COLUMN occurred_at SET NOT NULL;
ALTER TABLE outbox_event ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE outbox_delivery ALTER COLUMN event_id SET NOT NULL;
ALTER TABLE outbox_delivery ALTER COLUMN status SET NOT NULL;
ALTER TABLE outbox_delivery ALTER COLUMN attempt_count SET NOT NULL;
ALTER TABLE outbox_delivery ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE outbox_event ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE outbox_delivery ALTER COLUMN attempt_count SET DEFAULT 0;
ALTER TABLE outbox_delivery ALTER COLUMN updated_at SET DEFAULT now();

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON c.conrelid = t.oid
    WHERE t.relname = 'outbox_event' AND c.contype = 'p'
  ) THEN
    ALTER TABLE outbox_event ADD CONSTRAINT outbox_event_pkey PRIMARY KEY (event_id);
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON c.conrelid = t.oid
    WHERE t.relname = 'outbox_delivery' AND c.contype = 'p'
  ) THEN
    ALTER TABLE outbox_delivery ADD CONSTRAINT outbox_delivery_pkey PRIMARY KEY (event_id);
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON c.conrelid = t.oid
    WHERE t.relname = 'outbox_delivery' AND c.contype = 'f'
  ) THEN
    ALTER TABLE outbox_delivery
      ADD CONSTRAINT outbox_delivery_event_id_fkey
      FOREIGN KEY (event_id) REFERENCES outbox_event(event_id);
  END IF;
END $$;
