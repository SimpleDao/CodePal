package com.codepal.db;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;


public class SqliteDatabaseManager {
    private static final Object LOCK = new Object();
    private static String jdbcUrl = null;
    private static boolean isTablesInitialized = false;
    private static final Logger LOG = Logger.getInstance(SqliteDatabaseManager.class);

    public static Connection getConnection() {
        synchronized (LOCK) {
            if (jdbcUrl == null) {
                // 1. 初始化路径（只在第一次调用时执行）
                //C:\Users\18383\AppData\Local\JetBrains\IntelliJIdea2026.1\CPPlugin\db
                String dbDir = PathManager.getSystemPath() + File.separator + "CPPlugin" + File.separator + "db";
                File dir = new File(dbDir);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                LOG.info("dbDir: " + dbDir);
                System.out.println("dbDir: " + dbDir);
                String dbPath = dbDir + File.separator + "chat_history.db";

                jdbcUrl = "jdbc:sqlite:" + dbPath;

                try {
                    Class.forName("org.sqlite.JDBC");
                } catch (Exception e) {
                    System.err.println("驱动加载失败:" + e.getMessage());
                }
            }
            Connection connection = null;
            try {
                // 2. 每次都创建并返回一个新的连接对象
                connection = DriverManager.getConnection(jdbcUrl);

                // 设置忙等待与 WAL，缓解文件锁并发。
                try (Statement pragma = connection.createStatement()) {
                    pragma.execute("PRAGMA busy_timeout = 5000");
                    pragma.execute("PRAGMA journal_mode = WAL");
                }

                // 3. 确保表结构只初始化一次
                if (!isTablesInitialized) {
                    System.out.println("初始化表结构");
                    initTables(connection);
                    isTablesInitialized = true;
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
            return connection;
        }
    }

    private static void initTables(Connection connection) throws Exception {
        try (Statement stmt = connection.createStatement()) {

            // ─────────────────────────────────────────────
            // 1. sessions — 会话头表
            // ─────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id VARCHAR(64) PRIMARY KEY,
                    title VARCHAR(255) NOT NULL DEFAULT '\u65b0\u4f1a\u8bdd',
                    mode VARCHAR(20) NOT NULL DEFAULT 'chat',
                    model_id VARCHAR(64),
                    preview VARCHAR(500),
                    pinned INTEGER NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    project_path VARCHAR(1024)
                )
            """);
            // 按项目隔离会话：project_path 高频用于 WHERE 过滤，建索引
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_sessions_project ON sessions(project_path)
            """);

            // ─────────────────────────────────────────────
            // 2. messages — 消息头表（不含内容，内容下沉到 parts）
            // ─────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id VARCHAR(64) PRIMARY KEY,
                    session_id VARCHAR(64) NOT NULL,
                    role VARCHAR(20) NOT NULL,
                    seq INTEGER NOT NULL,
                    model_id VARCHAR(64),
                    tokens INTEGER,
                    qa_round INTEGER NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    token_usage TEXT,
                    meta TEXT
                )
            """);

            // ─────────────────────────────────────────────
            // 3. message_parts — 消息内容分片表
            //    kind: thinking / text / tool / error
            //    tool 的 content 为 NULL，元数据全在 meta(JSON)
            // ─────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS message_parts (
                    id VARCHAR(64) PRIMARY KEY,
                    message_id VARCHAR(64) NOT NULL,
                    session_id VARCHAR(64) NOT NULL,
                    kind VARCHAR(20) NOT NULL,
                    content TEXT,
                    meta TEXT,
                    status VARCHAR(20) NOT NULL DEFAULT 'done',
                    seq INTEGER NOT NULL,
                    created_at BIGINT NOT NULL
                )
            """);

            // ─────────────────────────────────────────────
            // 索引
            // ─────────────────────────────────────────────
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, seq)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_parts_message ON message_parts(message_id, seq)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_parts_session ON message_parts(session_id, seq)");

            // ─────────────────────────────────────────────
            // 4. model_configs — 模型配置表（聊天/补全两类）
            //    不再写入 IDE 缓存，统一落库，运行时从 DB 读取。
            // ─────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS model_configs (
                    id VARCHAR(64) PRIMARY KEY,
                    type VARCHAR(10) NOT NULL DEFAULT 'chat',
                    name VARCHAR(255) NOT NULL,
                    api_key VARCHAR(1024),
                    api_base VARCHAR(1024),
                    max_tokens INTEGER,
                    max_output INTEGER,
                    temperature REAL,
                    api_format VARCHAR(20),
                    supports_vision INTEGER NOT NULL DEFAULT 0,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    is_current INTEGER NOT NULL DEFAULT 0
                )
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_model_configs_type ON model_configs(type, sort_order)
            """);

            // ─────────────────────────────────────────────
            // 5. model_pricing — 单模型计费配置表（按模型名维度，与 model_configs 解耦）
            //    所有价格以「每 unit 个 token」计价，默认 unit=1,000,000。
            //    计费是模型可选项，未配置/未启用时费用由 PricingCalculator 兜底单价估算。
            // ─────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS model_pricing (
                    model_name VARCHAR(255) PRIMARY KEY,
                    enabled INTEGER NOT NULL DEFAULT 0,
                    currency VARCHAR(8) NOT NULL DEFAULT '¥',
                    unit INTEGER NOT NULL DEFAULT 1000000,
                    price_cache_hit REAL NOT NULL DEFAULT 0,
                    price_cache_miss REAL NOT NULL DEFAULT 0,
                    price_output REAL NOT NULL DEFAULT 0,
                    peak_enabled INTEGER NOT NULL DEFAULT 0,
                    peak_start_hour INTEGER NOT NULL DEFAULT 0,
                    peak_start_minute INTEGER NOT NULL DEFAULT 0,
                    peak_end_hour INTEGER NOT NULL DEFAULT 23,
                    peak_end_minute INTEGER NOT NULL DEFAULT 0,
                    peak_multiplier REAL NOT NULL DEFAULT 1.0
                )
            """);
            // 旧库补列（已存在则忽略）：高峰起止的分钟位 + 多时段窗口 JSON
            addColumnIfMissing(stmt, "model_pricing", "peak_start_minute", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(stmt, "model_pricing", "peak_end_minute", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(stmt, "model_pricing", "peak_windows", "TEXT");

            // ─────────────────────────────────────────────
            // 6. session_todos — 待办列表持久化（按会话维度）
            //    TodoManager 每次变更落库；会话激活/重启时读取回填。
            //    todos_json 为 TodoItem[] 的 JSON 数组文本。
            // ─────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS session_todos (
                    session_id VARCHAR(64) PRIMARY KEY,
                    todos_json TEXT,
                    updated_at BIGINT NOT NULL
                )
            """);

            // ─────────────────────────────────────────────
            // 6. data_sources — 外部数据源配置表（MySQL / SQLite）
            //    密码明文保存（与 model_configs.api_key 同款处理，不加密）。
            //    type: "mysql" | "sqlite"
            //    sqlite 类型时 host/port/user 可为空，db_name 为文件路径或库名。
            // ─────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS data_sources (
                    id VARCHAR(64) PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    type VARCHAR(20) NOT NULL DEFAULT 'mysql',
                    host VARCHAR(255),
                    port INTEGER,
                    db_name VARCHAR(255),
                    user VARCHAR(255),
                    password VARCHAR(1024),
                    sort_order INTEGER NOT NULL DEFAULT 0
                )
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_data_sources_sort ON data_sources(sort_order, name)
            """);

            // ─────────────────────────────────────────────
            // 7. session_model_usage — 会话×模型 维度 token 用量（圆环"会话累计（按模型）"）
            //    每次请求 usage 到达按当前模型原子增量 upsert；会话激活时整表回填。
            // ─────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS session_model_usage (
                    session_id VARCHAR(64) NOT NULL,
                    model_name VARCHAR(255) NOT NULL,
                    prompt_tokens INTEGER NOT NULL DEFAULT 0,
                    completion_tokens INTEGER NOT NULL DEFAULT 0,
                    cache_hit_tokens INTEGER NOT NULL DEFAULT 0,
                    cache_miss_tokens INTEGER NOT NULL DEFAULT 0,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (session_id, model_name)
                )
            """);
        }
    }

    /** 旧库补列：SQLite 无 ADD COLUMN IF NOT EXISTS，已存在时抛错直接忽略 */
    private static void addColumnIfMissing(Statement stmt, String table, String column, String type) {
        try {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (Exception ignored) {
            // 列已存在，忽略
        }
    }

    public static void closeConnection(Connection connection) {
        synchronized (LOCK) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}
