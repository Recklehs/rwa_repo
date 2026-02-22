-- Reconcile legacy environments where users table shape drifted from current contract.
-- This migration is intentionally defensive so signup can work against pre-existing DBs.

CREATE TABLE IF NOT EXISTS users (
  user_id UUID PRIMARY KEY DEFAULT uuidv7(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  compliance_status TEXT NOT NULL DEFAULT 'PENDING',
  compliance_updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS user_id UUID;
ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS compliance_status TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS compliance_updated_at TIMESTAMPTZ;

UPDATE users
SET user_id = md5(random()::text || clock_timestamp()::text)::uuid
WHERE user_id IS NULL;

UPDATE users
SET created_at = COALESCE(created_at, now()),
    compliance_status = COALESCE(compliance_status, 'PENDING'),
    compliance_updated_at = COALESCE(compliance_updated_at, created_at, now());

ALTER TABLE users ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE users ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE users ALTER COLUMN compliance_status SET NOT NULL;
ALTER TABLE users ALTER COLUMN compliance_updated_at SET NOT NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON c.conrelid = t.oid
    WHERE t.relname = 'users' AND c.contype = 'p'
  ) THEN
    ALTER TABLE users ADD CONSTRAINT users_pkey PRIMARY KEY (user_id);
  END IF;
END $$;
