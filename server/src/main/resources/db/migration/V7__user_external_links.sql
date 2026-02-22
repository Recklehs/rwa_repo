-- 외부 회원 시스템 사용자 식별자와 내부 user_id 매핑 테이블
CREATE TABLE IF NOT EXISTS user_external_links (
  id BIGSERIAL PRIMARY KEY,
  provider TEXT NOT NULL,
  external_user_id TEXT NOT NULL,
  user_id UUID NOT NULL REFERENCES users(user_id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (provider, external_user_id),
  UNIQUE (provider, user_id)
);

CREATE INDEX IF NOT EXISTS idx_user_external_links_user_id
  ON user_external_links(user_id);
