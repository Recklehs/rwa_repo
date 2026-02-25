\set ON_ERROR_STOP on

DO $$
DECLARE
  target_user text := :'aggregate_user_id';
  observed_count bigint;
BEGIN
  IF target_user IS NULL OR length(trim(target_user)) = 0 THEN
    RAISE EXCEPTION 'aggregate_user_id is required';
  END IF;

  SELECT COUNT(*) INTO observed_count
  FROM outbox_event e
  JOIN outbox_delivery d ON d.event_id = e.event_id
  WHERE e.aggregate_id = target_user
    AND e.event_type = 'ComplianceApproved'
    AND (d.status = 'SEND_FAIL' OR d.attempt_count > 0);

  IF observed_count = 0 THEN
    RAISE EXCEPTION 'T5 failed: SEND_FAIL/attempt evidence not found for user %', target_user;
  END IF;
END $$;

SELECT 'T5 send-fail evidence check passed' AS result;
