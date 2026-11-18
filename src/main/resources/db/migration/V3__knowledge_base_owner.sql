-- =====================================================================
-- V3: 知识库归属用户（knowledge_bases.user_id）
--
-- 此前知识库没有归属，任何登录用户都能查看、检索、删除别人的知识库。
-- 幂等脚本：可重复执行。应用启动时由 DatabaseInitConfig 自动执行（在建表之后）。
--
-- 存量数据：归属无法推断，统一划给最早注册的用户（id 最小）。
-- 如需调整：UPDATE knowledge_bases SET user_id = <用户id> WHERE id = <知识库id>;
-- user_id 仍为空的知识库对所有用户不可见。
-- =====================================================================
DO $$
BEGIN
    IF to_regclass('public.knowledge_bases') IS NOT NULL THEN
        ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS user_id BIGINT;
        CREATE INDEX IF NOT EXISTS idx_knowledge_bases_user_id ON knowledge_bases (user_id);
        IF to_regclass('public.users') IS NOT NULL THEN
            UPDATE knowledge_bases
               SET user_id = (SELECT MIN(id) FROM users)
             WHERE user_id IS NULL;
        END IF;
    END IF;
END $$;
