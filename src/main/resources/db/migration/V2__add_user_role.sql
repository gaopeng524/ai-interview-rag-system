-- =====================================================================
-- V2: 用户角色（USER / ADMIN）
--
-- 幂等脚本：可重复执行。应用启动时由 DatabaseInitConfig 自动执行，
-- 也可以手工执行：psql -U postgres -d <db> -f V2__add_user_role.sql
--
-- 授予管理员（只能由 DBA 手工执行，注册接口永远只创建 USER）：
--   UPDATE users SET role = 'ADMIN' WHERE username = '<管理员用户名>';
-- 角色写在 JWT 里，授予/撤销后需要该用户重新登录才生效（旧 token 最长 jwt.expiration-ms 后过期）。
-- =====================================================================

-- 仓库里此前没有 users 表的建表语句，新环境在这里补上（已存在则跳过）
CREATE TABLE IF NOT EXISTS users (
    id       BIGSERIAL PRIMARY KEY,
    username VARCHAR(64)  NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL
);

-- 1. 新增 role 列，存量用户全部回填为 USER
ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'USER';

-- 2. 取值约束：只允许 USER / ADMIN
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_users_role') THEN
        ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'));
    END IF;
END $$;
