import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.loongc.model.ChatSessionEntity;

import java.io.File;
import java.sql.*;


public class H2DatabaseManager {

    public static void main(String[] args) throws SQLException {
        String sql = "SELECT id,name,created_at,(SELECT COUNT(*) FROM chat_messages WHERE chat_messages.conversation_id = conversations.id) AS chat_message_count FROM conversations ORDER BY created_at DESC";
        Connection connection = getConnection();
        System.out.println(connection);
        PreparedStatement pstmt = connection.prepareStatement(sql); {
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    System.out.println(rs.getString("id") + " " + rs.getString("name") + " " + rs.getTimestamp("created_at") + " " + rs.getInt("chat_message_count"));
                }
            }
        }
    }

    private static final Object LOCK = new Object();
    private static String jdbcUrl = null;
    private static boolean isTablesInitialized = false;
    private static final Logger LOG = Logger.getInstance(H2DatabaseManager.class);
    public static Connection getConnection(){
        synchronized (LOCK) {
            String dbDir = PathManager.getSystemPath() + File.separator + "LoongCPlugin" + File.separator + "db";
            if (jdbcUrl == null) {
                // 1. 初始化路径（只在第一次调用时执行）
                //String dbDir = PathManager.getSystemPath() + File.separator + "LoongCPlugin" + File.separator + "db";
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
                    content CLOB NOT NULL,     -- 用 CLOB 存储超长文本，防止字符串溢出
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
