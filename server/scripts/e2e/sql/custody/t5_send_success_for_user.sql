\set ON_ERROR_STOP on

DO $$
DECLARE
  target_user text := :'aggregate_user_id';
  success_count bigint;
BEGIN
  IF target_user IS NULL OR length(trim(target_user)) = 0 THEN
    RAISE EXCEPTION 'aggregate_user_id is required';
  END IF;

  SELECT COUNT(*) INTO success_count
  FROM outbox_event e
  JOIN outbox_delivery d ON d.event_id = e.event_id
  WHERE e.aggregate_id = target_user
    AND e.event_type = 'ComplianceApproved'
    AND d.status = 'SEND_SUCCESS';

  IF success_count = 0 THEN
    RAISE EXCEPTION 'T5 failed: SEND_SUCCESS not observed for user %', target_user;
  END IF;
END $$;

SELECT 'T5 send-success convergence check passed' AS result;
