-- PostgreSQL observability extension bootstrap.
-- Requires shared_preload_libraries to include pg_stat_statements and auto_explain.

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Keep query ID statistics broad for benchmark sessions.
ALTER SYSTEM SET pg_stat_statements.track = 'all';
ALTER SYSTEM SET pg_stat_statements.max = '10000';

-- auto_explain defaults for perf regression analysis.
ALTER SYSTEM SET auto_explain.log_min_duration = '200ms';
ALTER SYSTEM SET auto_explain.log_analyze = 'on';
ALTER SYSTEM SET auto_explain.log_buffers = 'on';
ALTER SYSTEM SET auto_explain.log_nested_statements = 'on';

SELECT pg_reload_conf();
