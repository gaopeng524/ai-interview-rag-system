package com.example.demo.modules.knowledgebase.mapper;

import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 在真实 PostgreSQL 上执行 KnowledgeBaseMapper.xml 的每条 SQL 和 V3 迁移脚本，验证按用户隔离的查询语义。
 * 需要环境变量 TEST_PG_URL（如 jdbc:postgresql://localhost:5432/postgres）、TEST_PG_USER、TEST_PG_PASSWORD；
 * 没有设置时跳过。测试在独立的 schema 里进行，结束后删除。
 */
@EnabledIfEnvironmentVariable(named = "TEST_PG_URL", matches = ".+")
@DisplayName("KnowledgeBaseMapper（PostgreSQL 集成测试）")
class KnowledgeBaseMapperIntegrationTest {

    private static final String SCHEMA = "kb_mapper_it";
    private static SqlSessionFactory factory;

    @BeforeAll
    static void init() throws Exception {
        String url = System.getenv("TEST_PG_URL");
        String sep = url.contains("?") ? "&" : "?";
        PooledDataSource ds = new PooledDataSource("org.postgresql.Driver", url + sep + "currentSchema=" + SCHEMA,
                System.getenv().getOrDefault("TEST_PG_USER", "postgres"),
                System.getenv().getOrDefault("TEST_PG_PASSWORD", ""));
        try (var c = new PooledDataSource("org.postgresql.Driver", url,
                ds.getUsername(), ds.getPassword()).getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            st.execute("CREATE SCHEMA " + SCHEMA);
        }
        Configuration cfg = new Configuration(new Environment("it", new JdbcTransactionFactory(), ds));
        String resource = "mapper/KnowledgeBaseMapper.xml";
        try (InputStream in = KnowledgeBaseMapperIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (factory == null) return;
        try (SqlSession s = factory.openSession(true); Statement st = s.getConnection().createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private static KnowledgeBase kb(Long userId, String name, String hash, String category, String status) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setUserId(userId);
        kb.setName(name);
        kb.setFileHash(hash);
        kb.setCategory(category);
        kb.setVectorStatus(status);
        return kb;
    }

    @Test
    @DisplayName("建表、迁移脚本可重复执行；所有查询只返回当前用户的数据")
    void queriesAreScopedByUser() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            KnowledgeBaseMapper mapper = session.getMapper(KnowledgeBaseMapper.class);
            mapper.createTable();
            mapper.createTable();

            // V3 迁移：在已有表上重复执行不报错
            String v3 = new String(getClass().getClassLoader()
                    .getResourceAsStream("db/migration/V3__knowledge_base_owner.sql").readAllBytes(), StandardCharsets.UTF_8)
                    .replace("public.", SCHEMA + ".");
            try (Statement st = session.getConnection().createStatement()) {
                st.execute(v3);
                st.execute(v3);
            }

            KnowledgeBase a1 = kb(1L, "Java 并发", "h1", "后端", "COMPLETED");
            KnowledgeBase a2 = kb(1L, "Redis 笔记", "h2", "中间件", "PROCESSING");
            KnowledgeBase b1 = kb(2L, "Java 面经", "h1", "面经", "COMPLETED"); // 与 a1 文件哈希相同
            mapper.insert(a1);
            mapper.insert(a2);
            mapper.insert(b1);
            assertNotNull(a1.getId());

            assertEquals(1L, mapper.findById(a1.getId()).getUserId());
            assertEquals(2, mapper.findAllByUser(1L).size());
            assertEquals(1, mapper.findAllByUser(2L).size());
            assertTrue(mapper.findAllByUser(3L).isEmpty());
            assertEquals(List.of(b1.getId()), mapper.findIdsByUser(2L));

            // 同一个文件哈希，各自只能查到自己的
            assertEquals(a1.getId(), mapper.findByFileHash(1L, "h1").getId());
            assertEquals(b1.getId(), mapper.findByFileHash(2L, "h1").getId());
            assertNull(mapper.findByFileHash(3L, "h1"));

            assertEquals(1, mapper.searchByName(1L, "java").size());
            assertEquals("Java 面经", mapper.searchByName(2L, "JAVA").get(0).getName());
            assertEquals(List.of("中间件", "后端"), mapper.findAllCategories(1L).stream().sorted().toList());
            assertEquals(List.of("面经"), mapper.findAllCategories(2L));
            assertEquals(2, mapper.countAll(1L));
            assertEquals(1, mapper.countByVectorStatus(1L, "COMPLETED"));
            assertEquals(0, mapper.countByVectorStatus(2L, "PROCESSING"));

            assertEquals(2, mapper.findByIds(List.of(a1.getId(), b1.getId())).size());
            mapper.updateVectorStatus(a2.getId(), "COMPLETED", null, 7);
            assertEquals(7, mapper.findById(a2.getId()).getChunkCount());
            mapper.deleteById(a1.getId());
            assertNull(mapper.findById(a1.getId()));
            assertEquals(1, mapper.countAll(1L));
        }
    }
}
