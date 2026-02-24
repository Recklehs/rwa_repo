-- users.user_id는 DB 기본값에 의존하지 않고, 애플리케이션에서 UUIDv7을 생성해 저장한다.
ALTER TABLE IF EXISTS users
  ALTER COLUMN user_id DROP DEFAULT;
