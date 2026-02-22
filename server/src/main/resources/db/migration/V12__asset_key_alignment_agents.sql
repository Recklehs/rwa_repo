-- Align public asset bucket keys and unit_id format with AGENTS global rules.
-- NOTE:
-- - class_key is migrated from legacy buckets to MPAREA_* range buckets.
-- - class_id is intentionally kept unchanged for backward compatibility with existing rows and references.

-- Guard: fail fast if legacy and target class_key rows already coexist for the same kapt_code.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM classes legacy
    JOIN classes modern
      ON modern.kapt_code = legacy.kapt_code
     AND modern.class_id <> legacy.class_id
     AND modern.class_key = CASE legacy.class_key
       WHEN 'MPAREA_60' THEN 'MPAREA_LE_60'
       WHEN 'MPAREA_85' THEN 'MPAREA_60_85'
       WHEN 'MPAREA_135' THEN 'MPAREA_85_135'
       WHEN 'MPAREA_136' THEN 'MPAREA_GE_136'
       ELSE legacy.class_key
     END
    WHERE legacy.class_key IN ('MPAREA_60', 'MPAREA_85', 'MPAREA_135', 'MPAREA_136')
  ) THEN
    RAISE EXCEPTION
      'V12 migration conflict: legacy class_key and target class_key coexist for the same kapt_code';
  END IF;
END $$;

-- 1) class_key rename
UPDATE classes
SET class_key = CASE class_key
  WHEN 'MPAREA_60' THEN 'MPAREA_LE_60'
  WHEN 'MPAREA_85' THEN 'MPAREA_60_85'
  WHEN 'MPAREA_135' THEN 'MPAREA_85_135'
  WHEN 'MPAREA_136' THEN 'MPAREA_GE_136'
  ELSE class_key
END
WHERE class_key IN ('MPAREA_60', 'MPAREA_85', 'MPAREA_135', 'MPAREA_136');

-- Guard: fail fast if target unit_id collisions would occur after format rewrite.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM (
      SELECT
        c.kapt_code || '|' || c.class_key || '|' || lpad(u.unit_no::text, 5, '0') AS target_unit_id,
        COUNT(*) AS cnt
      FROM units u
      JOIN classes c ON c.class_id = u.class_id
      GROUP BY 1
      HAVING COUNT(*) > 1
    ) collisions
  ) THEN
    RAISE EXCEPTION
      'V12 migration conflict: duplicate target unit_id values detected';
  END IF;
END $$;

-- 2) unit_id format rewrite: {kaptCode}|{classKey}|{pad5(unitNo)}
UPDATE units u
SET unit_id = c.kapt_code || '|' || c.class_key || '|' || lpad(u.unit_no::text, 5, '0')
FROM classes c
WHERE c.class_id = u.class_id
  AND u.unit_id IS DISTINCT FROM (c.kapt_code || '|' || c.class_key || '|' || lpad(u.unit_no::text, 5, '0'));
