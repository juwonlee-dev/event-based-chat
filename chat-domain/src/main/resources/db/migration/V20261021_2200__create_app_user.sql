CREATE TABLE app_user (
    id            uuid         PRIMARY KEY DEFAULT uuidv7(),
    username      varchar(50)  NOT NULL,
    password_hash varchar(100) NOT NULL,
    display_name  varchar(50)  NOT NULL,
    role          varchar(20)  NOT NULL,
    category      varchar(30),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT app_user_role_ck CHECK (role IN ('USER', 'CUSTOMER', 'ADMIN')),
    CONSTRAINT app_user_category_ck CHECK (category IN (
        'INSURANCE', 'BANK', 'HOSPITAL', 'FURNITURE', 'REAL_ESTATE',
        'COMMUNITY_CENTER', 'B2B_SALES', 'CUSTOMER_CENTER', 'CAR_SALES', 'TELECOM')),
    -- 관리자만 분류코드가 없다
    CONSTRAINT app_user_role_category_ck CHECK ((role = 'ADMIN') = (category IS NULL))
);

CREATE UNIQUE INDEX app_user_username_uq ON app_user (lower(username));
