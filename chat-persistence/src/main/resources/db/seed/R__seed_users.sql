-- 로컬 개발용 시드 사용자(로컬 프로필에서만 적용). 비밀번호는 모두 동일하며 README에 적는다.
INSERT INTO app_user (username, password_hash, display_name, role, category)
VALUES
    ('admin', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '관리자', 'ADMIN', NULL),
    ('insurance.user', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '보험 상담원', 'USER', 'INSURANCE'),
    ('insurance.customer', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '보험 고객', 'CUSTOMER', 'INSURANCE'),
    ('bank.user', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '은행 상담원', 'USER', 'BANK'),
    ('bank.customer', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '은행 고객', 'CUSTOMER', 'BANK'),
    ('hospital.user', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '병원 상담원', 'USER', 'HOSPITAL'),
    ('hospital.customer', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '병원 고객', 'CUSTOMER', 'HOSPITAL'),
    ('furniture.user', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '가구 상담원', 'USER', 'FURNITURE'),
    ('furniture.customer', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '가구 고객', 'CUSTOMER', 'FURNITURE'),
    ('real-estate.user', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '부동산 중개 상담원', 'USER', 'REAL_ESTATE'),
    ('real-estate.customer', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '부동산 중개 고객', 'CUSTOMER', 'REAL_ESTATE'),
    ('community-center.user', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '주민센터 상담원', 'USER', 'COMMUNITY_CENTER'),
    ('community-center.customer', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '주민센터 고객', 'CUSTOMER', 'COMMUNITY_CENTER'),
    ('b2b-sales.user', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', 'B2B 영업 상담원', 'USER', 'B2B_SALES'),
    ('b2b-sales.customer', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', 'B2B 영업 고객', 'CUSTOMER', 'B2B_SALES'),
    ('customer-center.user', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '고객센터 상담원', 'USER', 'CUSTOMER_CENTER'),
    ('customer-center.customer', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '고객센터 고객', 'CUSTOMER', 'CUSTOMER_CENTER'),
    ('car-sales.user', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '자동차 판매 상담원', 'USER', 'CAR_SALES'),
    ('car-sales.customer', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '자동차 판매 고객', 'CUSTOMER', 'CAR_SALES'),
    ('telecom.user', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '통신사 상담원', 'USER', 'TELECOM'),
    ('telecom.customer', '$2a$10$zifWT7L2nQlr24IAtLUVgOwglI5MPT8m5DwUT/TM2.PQtWSdXtLFy', '통신사 고객', 'CUSTOMER', 'TELECOM')
ON CONFLICT ((lower(username))) DO NOTHING;
