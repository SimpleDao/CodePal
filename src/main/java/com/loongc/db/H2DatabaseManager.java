package com.loongc.db;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;


public class H2DatabaseManager {
    private static final Object LOCK = new Object();
    private static String jdbcUrl = null;
    private static boolean isTablesInitialized = false;
    private static final Logger LOG = Logger.getInstance(H2DatabaseManager.class);
    public static Connection getConnection(){
        synchronized (LOCK) {
            if (jdbcUrl == null) {
                // 1. 初始化路径（只在第一次调用时执行）
                //E:\P-project\Java\idea-plugin-s\LoongC\build\idea-sandbox\system\LoongCPlugin\db
                String dbDir = PathManager.getSystemPath() + File.separator + "LoongCPlugin" + File.separator + "db";
                File dir = new File(dbDir);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                LOG.info("dbDir: " + dbDir);
                String dbPath = dbDir + File.separator + "chat_history";
                jdbcUrl = "jdbc:h2:file:" + dbPath + ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";

                try {
                    Class.forName("org.h2.Driver");
                } catch (Exception e) {
                    System.err.println("驱动加载失败:" + e.getMessage());
                }
            }
            Connection connection = null;
            try {
                // 2. 每次都创建并返回一个新的连接对象
                connection = DriverManager.getConnection(jdbcUrl, "sa", "");

                // 3. 确保表结构只初始化一次
                if (!isTablesInitialized) {
                    // 注意：建议修改你的 initTables() 方法，让它接收这个 connection 传进去使用
                    // 或者在 initTables 内部自己 getConnection() 并用完关闭
                    initTables(connection);
                    isTablesInitialized = true;
                }

            }catch (Exception e){
                e.printStackTrace();
            }
            return connection;
        }
    }

    private static void initTables(Connection connection) throws Exception {
        try (Statement stmt = connection.createStatement()) {

            stmt.execute("""
            CREATE TABLE IF NOT EXISTS conversations (
                id VARCHAR(64) PRIMARY KEY,
                name VARCHAR(255) NOT NULL,        -- 会话名称（可以是用户的第一个问题）
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """);

            // 创建会话历史表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id VARCHAR(64) PRIMARY KEY,
                    conversation_id VARCHAR(64) NOT NULL,
                    role VARCHAR(20) NOT NULL, -- 'user' 或 'assistant' 'tool'
                    content CLOB,     -- 用 CLOB 存储超长文本，防止字符串溢出
                    tool_calls_json CLOB,           -- 工具调用
                    tool_call_id VARCHAR(64),  -- 工具调用id
                    name VARCHAR(255),         -- 工具名称
                    reasoning_content CLOB,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            // 建立索引，保证百万级长对话下的检索速度
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_conv_id ON chat_messages(conversation_id)");
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
