-- AgentTrail Phase 2B business analytics database import.
-- This script is intentionally separate from spring.sql.init; run it once after loading sakila data.
-- Inject the analytics_ro password at deployment time; never commit it here.
USE agenttrail;

-- Seed the deterministic analyst fixtures used by permission Golden Tasks.
INSERT INTO sys_user (id, username, password, nickname, status, created_at, updated_at)
VALUES
    (5, 'sales_east', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'East sales', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
    (6, 'sales_a1', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Product sales A1', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
    (7, 'sales_a2', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Product sales A2', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
    (8, 'sales_b', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Platform sales', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
    (9, 'sales_south', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'South sales', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), status = VALUES(status), updated_at = VALUES(updated_at);
INSERT INTO sys_user_role (user_id, role_id, created_at)
VALUES (5, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (6, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (7, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (8, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (9, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
ON DUPLICATE KEY UPDATE created_at = VALUES(created_at);
INSERT INTO sys_user_dept (user_id, dept_id, created_at)
VALUES (5, 2, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (6, 3, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (7, 3, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (8, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (9, 5, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
ON DUPLICATE KEY UPDATE created_at = VALUES(created_at);

-- Views hide ownership columns from the AST rewriter. Keep source tables visible instead.
DROP VIEW IF EXISTS customer_list, film_list, nicer_but_slower_film_list,
    sales_by_film_category, sales_by_store, staff_list, actor_info;

-- The sakila staff/store tables overlap with AgentTrail RBAC concepts and are not needed by DataAgent.
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS staff, store;
SET FOREIGN_KEY_CHECKS = 1;

ALTER TABLE rental
    ADD COLUMN user_id BIGINT NULL COMMENT 'business owner, references sys_user.id',
    ADD COLUMN dept_id BIGINT NULL COMMENT 'owning department, references sys_dept.id',
    ADD KEY idx_rental_dept (dept_id), ADD KEY idx_rental_user (user_id);
ALTER TABLE payment
    ADD COLUMN user_id BIGINT NULL COMMENT 'business owner, references sys_user.id',
    ADD COLUMN dept_id BIGINT NULL COMMENT 'owning department, references sys_dept.id',
    ADD KEY idx_payment_dept (dept_id), ADD KEY idx_payment_user (user_id);

CREATE TABLE IF NOT EXISTS user_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT 'references sys_user.id',
    dept_id BIGINT NOT NULL COMMENT 'department snapshot for analytics',
    real_name VARCHAR(50) NOT NULL,
    id_card CHAR(18) NOT NULL COMMENT 'synthetic sensitive value; must be masked',
    home_address VARCHAR(200) NULL COMMENT 'synthetic sensitive value; must be masked',
    age INT NULL,
    education VARCHAR(20) NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_user_profile_user (user_id), KEY idx_user_profile_dept (dept_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='employee profile for masking demonstrations';

CREATE TABLE IF NOT EXISTS dim_dept (
    dept_id BIGINT NOT NULL, dept_name VARCHAR(100) NOT NULL, parent_id BIGINT NOT NULL,
    PRIMARY KEY (dept_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='read-only department dimension';

INSERT INTO dim_dept (dept_id, dept_name, parent_id)
SELECT id, name, parent_id FROM sys_dept
ON DUPLICATE KEY UPDATE dept_name = VALUES(dept_name), parent_id = VALUES(parent_id);

INSERT INTO user_profile (user_id, dept_id, real_name, id_card, home_address, age, education)
SELECT u.id,
       COALESCE((SELECT MIN(ud.dept_id) FROM sys_user_dept ud WHERE ud.user_id = u.id), 0),
       COALESCE(u.nickname, u.username),
       CONCAT('1101011990', LPAD(u.id, 7, '0'), 'X'),
       CONCAT('synthetic-address-', u.id), 30 + MOD(u.id, 10), 'college'
FROM sys_user u
WHERE u.id BETWEEN 1 AND 9
ON DUPLICATE KEY UPDATE dept_id = VALUES(dept_id), real_name = VALUES(real_name),
    id_card = VALUES(id_card), home_address = VALUES(home_address), age = VALUES(age), education = VALUES(education);

-- Deterministic ownership assignment keeps Golden Task reference results reproducible.
UPDATE rental r
JOIN (SELECT 0 slot, 5 user_id, 2 dept_id UNION ALL SELECT 1, 6, 3
      UNION ALL SELECT 2, 7, 3 UNION ALL SELECT 3, 8, 4 UNION ALL SELECT 4, 9, 5) m
  ON m.slot = MOD(r.customer_id, 5)
SET r.user_id = m.user_id, r.dept_id = m.dept_id;
UPDATE payment p JOIN rental r ON r.rental_id = p.rental_id
SET p.user_id = r.user_id, p.dept_id = r.dept_id;

-- The read-only account is the hard security boundary. Never grant agent_* or sys_* tables.
CREATE USER IF NOT EXISTS 'analytics_ro'@'%' IDENTIFIED BY '<inject-at-deploy-time>';
GRANT SELECT ON agenttrail.rental TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.payment TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.customer TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.inventory TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.film TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.film_actor TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.film_category TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.film_text TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.actor TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.category TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.language TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.address TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.city TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.country TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.user_profile TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.dim_dept TO 'analytics_ro'@'%';
FLUSH PRIVILEGES;

-- Deployment validation: these queries must all return zero.
SELECT COUNT(*) AS rental_missing_owner FROM rental WHERE user_id IS NULL OR dept_id IS NULL;
SELECT COUNT(*) AS payment_missing_owner FROM payment WHERE user_id IS NULL OR dept_id IS NULL;
SELECT COUNT(*) AS rental_inconsistent_owner FROM rental r
WHERE NOT EXISTS (SELECT 1 FROM sys_user_dept ud WHERE ud.user_id = r.user_id AND ud.dept_id = r.dept_id);
SELECT COUNT(*) AS payment_inconsistent_owner FROM payment p
WHERE NOT EXISTS (SELECT 1 FROM sys_user_dept ud WHERE ud.user_id = p.user_id AND ud.dept_id = p.dept_id);
