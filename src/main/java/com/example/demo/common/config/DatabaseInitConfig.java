package com.example.demo.common.config;

import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 数据库初始化配置
 * 应用启动时自动创建表与字段
 */
@Configuration
@Slf4j
public class DatabaseInitConfig {

    @Bean
    public CommandLineRunner initDatabase(KnowledgeBaseMapper knowledgeBaseMapper, JdbcTemplate jdbcTemplate) {
        return args -> {
            log.info("初始化数据库表...");
            // 版本化迁移脚本（幂等）：按文件名顺序执行 classpath:db/migration/*.sql
            runMigrations(jdbcTemplate);
            try {
                knowledgeBaseMapper.createTable();
                log.info("知识库表检查/创建成功");
            } catch (Exception e) {
                log.error("创建知识库表失败: {}", e.getMessage(), e);
            }

            try {
                // 确保 chat_sessions 表字段完整
                jdbcTemplate.execute("ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS is_pinned BOOLEAN DEFAULT FALSE");
                jdbcTemplate.execute("ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                jdbcTemplate.execute("ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");

                // 确保 chat_messages 表字段完整
                jdbcTemplate.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");

                // 创建会话与知识库关联表
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS chat_session_knowledge_bases (
                        session_id BIGINT NOT NULL,
                        knowledge_base_id BIGINT NOT NULL,
                        PRIMARY KEY (session_id, knowledge_base_id)
                    )
                """);
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_session_kb_session_id ON chat_session_knowledge_bases(session_id)");
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_session_kb_kb_id ON chat_session_knowledge_bases(knowledge_base_id)");

                // 创建面试会话表与题目表
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS interview_sessions (
                        id BIGSERIAL PRIMARY KEY,
                        session_id VARCHAR(64) NOT NULL UNIQUE,
                        user_id BIGINT,
                        skill_id VARCHAR(64) NOT NULL DEFAULT 'java',
                        difficulty VARCHAR(32) NOT NULL DEFAULT 'middle',
                        resume_id BIGINT,
                        resume_text TEXT,
                        jd_text TEXT,
                        total_questions INT NOT NULL DEFAULT 5,
                        current_question_index INT NOT NULL DEFAULT 0,
                        status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
                        evaluate_status VARCHAR(32) DEFAULT 'PENDING',
                        evaluate_error TEXT,
                        overall_score NUMERIC(5,2),
                        overall_feedback TEXT,
                        strengths TEXT,
                        improvements TEXT,
                        category_scores TEXT,
                        source_type VARCHAR(32) DEFAULT 'TEXT',
                        knowledge_base_id BIGINT,
                        interview_category VARCHAR(64),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        completed_at TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_interview_sessions_user_id ON interview_sessions(user_id)");
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_interview_sessions_session_id ON interview_sessions(session_id)");

                // 安全迁移既有表字段为 TIMESTAMP WITHOUT TIME ZONE
                try {
                    jdbcTemplate.execute("ALTER TABLE interview_sessions ALTER COLUMN created_at TYPE TIMESTAMP USING created_at::timestamp");
                    jdbcTemplate.execute("ALTER TABLE interview_sessions ALTER COLUMN completed_at TYPE TIMESTAMP USING completed_at::timestamp");
                    jdbcTemplate.execute("ALTER TABLE interview_sessions ALTER COLUMN updated_at TYPE TIMESTAMP USING updated_at::timestamp");
                } catch (Exception ignored) {}

                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS interview_questions (
                        id BIGSERIAL PRIMARY KEY,
                        session_id VARCHAR(64) NOT NULL,
                        question_index INT NOT NULL,
                        question TEXT NOT NULL,
                        type VARCHAR(32) DEFAULT 'TECHNICAL',
                        category VARCHAR(64),
                        topic_summary TEXT,
                        user_answer TEXT,
                        score NUMERIC(5,2),
                        feedback TEXT,
                        is_follow_up BOOLEAN DEFAULT FALSE,
                        parent_question_index INT,
                        reference_answer TEXT,
                        key_points TEXT,
                        scoring_rubric TEXT,
                        source_context TEXT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT uk_session_question UNIQUE (session_id, question_index)
                    )
                """);
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_interview_questions_session_id ON interview_questions(session_id)");

                // 安全迁移题目表字段为 TIMESTAMP WITHOUT TIME ZONE
                try {
                    jdbcTemplate.execute("ALTER TABLE interview_questions ALTER COLUMN created_at TYPE TIMESTAMP USING created_at::timestamp");
                    jdbcTemplate.execute("ALTER TABLE interview_questions ALTER COLUMN updated_at TYPE TIMESTAMP USING updated_at::timestamp");
                } catch (Exception ignored) {}

                // 创建并初始化大模型供应商配置表
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS llm_provider_config (
                        id VARCHAR(64) PRIMARY KEY,
                        base_url VARCHAR(255) NOT NULL,
                        api_key VARCHAR(255),
                        model VARCHAR(128) NOT NULL,
                        embedding_model VARCHAR(128),
                        embedding_dimensions INT DEFAULT 1536,
                        supports_embedding BOOLEAN DEFAULT FALSE,
                        temperature NUMERIC(3,2) DEFAULT 0.2,
                        is_default BOOLEAN DEFAULT FALSE,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);

                try {
                    jdbcTemplate.execute("ALTER TABLE llm_provider_config ADD COLUMN IF NOT EXISTS is_default_embedding BOOLEAN DEFAULT FALSE");
                    jdbcTemplate.execute("ALTER TABLE llm_provider_config ALTER COLUMN created_at TYPE TIMESTAMP USING created_at::timestamp");
                    jdbcTemplate.execute("ALTER TABLE llm_provider_config ALTER COLUMN updated_at TYPE TIMESTAMP USING updated_at::timestamp");
                } catch (Exception ignored) {}

                // 预置默认供应商（小米文字默认，Dashscope 向量默认）
                jdbcTemplate.execute("""
                    INSERT INTO llm_provider_config (id, base_url, api_key, model, supports_embedding, temperature, is_default, is_default_embedding)
                    VALUES ('xiaomi', 'https://api.mimo.mi.com/v1', '', 'mimo-v2.5-pro', false, 0.2, true, false)
                    ON CONFLICT (id) DO NOTHING
                """);
                try {
                    jdbcTemplate.execute("UPDATE llm_provider_config SET model = 'mimo-v2.5-pro' WHERE id IN ('xiaomi', 'mimo') AND (model = 'mimo-v1' OR model IS NULL)");
                } catch (Exception ignored) {}
                jdbcTemplate.execute("""
                    INSERT INTO llm_provider_config (id, base_url, api_key, model, supports_embedding, temperature, is_default, is_default_embedding)
                    VALUES ('deepseek', 'https://api.deepseek.com', '', 'deepseek-v4-flash', false, 0.2, false, false)
                    ON CONFLICT (id) DO NOTHING
                """);
                jdbcTemplate.execute("""
                    INSERT INTO llm_provider_config (id, base_url, api_key, model, supports_embedding, temperature, is_default, is_default_embedding)
                    VALUES ('glm', 'https://open.bigmodel.cn/api/paas/v4', '', 'glm-4-flash', false, 0.2, false, false)
                    ON CONFLICT (id) DO NOTHING
                """);
                jdbcTemplate.execute("""
                    INSERT INTO llm_provider_config (id, base_url, api_key, model, embedding_model, embedding_dimensions, supports_embedding, temperature, is_default, is_default_embedding)
                    VALUES ('dashscope', 'https://dashscope.aliyuncs.com/compatible-mode', '', 'qwen-turbo', 'text-embedding-v3', 1536, true, 0.2, false, true)
                    ON CONFLICT (id) DO NOTHING
                """);
                try {
                    jdbcTemplate.execute("UPDATE llm_provider_config SET is_default_embedding = TRUE WHERE id = 'dashscope' AND NOT EXISTS (SELECT 1 FROM llm_provider_config WHERE is_default_embedding = TRUE AND id != 'dashscope')");
                } catch (Exception ignored) {}

                // 创建并初始化简历基础表与分析报告表
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS resumes (
                        id BIGSERIAL PRIMARY KEY,
                        user_id BIGINT NOT NULL,
                        filename VARCHAR(255) NOT NULL,
                        file_size BIGINT,
                        file_hash VARCHAR(64),
                        content_type VARCHAR(128),
                        resume_text TEXT,
                        analyze_status VARCHAR(32) DEFAULT 'PENDING',
                        analyze_error TEXT,
                        latest_score NUMERIC(5,2),
                        access_count INT DEFAULT 0,
                        interview_count INT DEFAULT 0,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_resumes_user_id ON resumes(user_id)");
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_resumes_file_hash ON resumes(user_id, file_hash)");

                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS resume_analyses (
                        id BIGSERIAL PRIMARY KEY,
                        resume_id BIGINT NOT NULL,
                        overall_score NUMERIC(5,2),
                        content_score NUMERIC(5,2),
                        structure_score NUMERIC(5,2),
                        skill_match_score NUMERIC(5,2),
                        expression_score NUMERIC(5,2),
                        project_score NUMERIC(5,2),
                        summary TEXT,
                        strengths TEXT,
                        suggestions TEXT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
                // 创建面试日程管理表
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS interview_schedules (
                        id BIGSERIAL PRIMARY KEY,
                        user_id BIGINT NOT NULL DEFAULT 1,
                        company_name VARCHAR(100) NOT NULL,
                        position VARCHAR(100) NOT NULL,
                        interview_time TIMESTAMP NOT NULL,
                        interview_type VARCHAR(30) DEFAULT 'VIDEO',
                        meeting_link TEXT,
                        round_number INT DEFAULT 1,
                        interviewer VARCHAR(100),
                        notes TEXT,
                        status VARCHAR(30) DEFAULT 'PENDING',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
                // 创建知识库考题表
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_base_questions (
                        id BIGSERIAL PRIMARY KEY,
                        knowledge_base_id BIGINT NOT NULL,
                        skill_id VARCHAR(64) DEFAULT 'knowledge-base',
                        difficulty VARCHAR(16) DEFAULT 'MEDIUM',
                        type VARCHAR(64) DEFAULT 'TECHNICAL',
                        category VARCHAR(64) DEFAULT '通用',
                        question TEXT NOT NULL,
                        topic_summary VARCHAR(300),
                        reference_answer TEXT,
                        key_points TEXT,
                        scoring_rubric TEXT,
                        follow_ups TEXT,
                        source_context TEXT,
                        status VARCHAR(32) DEFAULT 'ACTIVE',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_kb_questions_kb_id ON knowledge_base_questions(knowledge_base_id)");
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_kb_questions_category ON knowledge_base_questions(category)");

                log.info("会话知识库关联表、面试表、大模型配置表、简历表、面试日程表、知识库考题表结构检查/创建成功");
            } catch (Exception e) {
                log.error("初始化数据库结构失败: {}", e.getMessage(), e);
            }
        };
    }

    /**
     * 执行 classpath:db/migration 下的 SQL 迁移脚本（按文件名排序）。
     * 脚本必须写成幂等的（IF NOT EXISTS 等），因为每次启动都会执行。
     * 整个文件作为一条语句交给 PostgreSQL（pgjdbc 支持多语句），以便脚本里使用 DO $$ ... $$ 块。
     */
    static void runMigrations(JdbcTemplate jdbcTemplate) {
        try {
            org.springframework.core.io.Resource[] scripts =
                    new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                            .getResources("classpath:db/migration/*.sql");
            java.util.Arrays.sort(scripts, java.util.Comparator.comparing(
                    r -> java.util.Objects.requireNonNullElse(r.getFilename(), "")));
            for (org.springframework.core.io.Resource script : scripts) {
                String sql = script.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                jdbcTemplate.execute(sql);
                log.info("数据库迁移脚本执行完成: {}", script.getFilename());
            }
        } catch (Exception e) {
            log.error("数据库迁移脚本执行失败: {}", e.getMessage(), e);
        }
    }
}
