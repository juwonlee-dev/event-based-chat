#!/usr/bin/env bash
# 최초 기동(빈 볼륨)일 때만 실행된다. 다시 적용하려면 docker compose down -v
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
    CREATE ROLE ${DB_OWNER_USER} LOGIN PASSWORD '${DB_OWNER_PASSWORD}';
    CREATE ROLE ${DB_APP_USER} LOGIN PASSWORD '${DB_APP_PASSWORD}';
    CREATE DATABASE ${DB_NAME} OWNER ${DB_OWNER_USER};

    ALTER ROLE ${DB_APP_USER} SET lock_timeout = '1s';
    ALTER ROLE ${DB_APP_USER} SET statement_timeout = '3s';
    ALTER ROLE ${DB_APP_USER} SET idle_in_transaction_session_timeout = '10s';
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${DB_NAME}" <<-SQL
    REVOKE ALL ON DATABASE ${DB_NAME} FROM PUBLIC;
    GRANT CONNECT ON DATABASE ${DB_NAME} TO ${DB_APP_USER};
    GRANT USAGE ON SCHEMA public TO ${DB_APP_USER};

    -- Flyway(소유자)가 만드는 테이블에 앱 계정 권한을 자동 부여한다.
    -- 추가 전용 테이블은 해당 마이그레이션에서 UPDATE·DELETE를 회수한다.
    ALTER DEFAULT PRIVILEGES FOR ROLE ${DB_OWNER_USER} IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${DB_APP_USER};
    ALTER DEFAULT PRIVILEGES FOR ROLE ${DB_OWNER_USER} IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO ${DB_APP_USER};

    CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
SQL
