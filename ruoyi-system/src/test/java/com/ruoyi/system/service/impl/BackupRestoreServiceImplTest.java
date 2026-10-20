package com.ruoyi.system.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.SimpleResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.dto.ParsedInsert;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BackupRestoreServiceImpl} 备份能力单元测试（U1 场景 1-7）。
 * <p>
 * 导出逻辑用真实 H2 内存库（MySQL 兼容模式）跑真导出。H2 无 SHOW CREATE TABLE
 * 语法（MySQL 专有），测试经 {@link H2ShowCreateDataSource} 把该语句翻译为 H2
 * 的 SCRIPT NODATA TABLE 等价物，生产代码保持 MySQL 语义不变；写锁互斥与
 * 消费者检查用直接实例化 + 静态锁状态控制验证（锁检查在各方法首行，不触依赖字段）。
 * <p>
 * H2 未加引号标识符统一大写化，表名/列名断言按大写书写。
 *
 * @author ruoyi
 */
class BackupRestoreServiceImplTest
{
    /** H2 大写化的测试表名 */
    private static final String T_SYS_USER = "SYS_USER";

    private static final String T_NOTE_NOTE = "NOTE_NOTE";

    /** JUnit 临时根目录（本类多字段 @TempDir 会共享同一目录，子目录手动划分） */
    @TempDir
    Path tempRoot;

    /** 备份落盘目录（backupPath 槽位，独立于 profile 防递归） */
    private Path backupDir;

    /** 用户文件根目录（profile） */
    private Path profileDir;

    private BackupRestoreServiceImpl service;

    private JdbcDataSource rawDataSource;

    /** RuoYiConfig 静态字段原值，@AfterEach 恢复防止污染其他测试类 */
    private String originalProfile;

    private String originalBackupPath;

    @BeforeEach
    void setUp() throws IOException
    {
        originalProfile = RuoYiConfig.getProfile();
        originalBackupPath = RuoYiConfig.getBackupPath();
        backupDir = Files.createDirectories(tempRoot.resolve("backup"));
        profileDir = Files.createDirectories(tempRoot.resolve("profile"));
        // RuoYiConfig 静态配置项经实例 setter 绑定（对齐 profile 模式）
        new RuoYiConfig().setProfile(profileDir.toString());
        new RuoYiConfig().setBackupPath(backupDir.toString());

        rawDataSource = newH2DataSource();
        service = new BackupRestoreServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "dataSource",
                new H2ShowCreateDataSource(rawDataSource));
    }

    @AfterEach
    void tearDown()
    {
        // 兜底释放静态写锁，防止测试间泄漏
        BackupRestoreServiceImpl.releaseLock();
        new RuoYiConfig().setProfile(originalProfile);
        new RuoYiConfig().setBackupPath(originalBackupPath);
    }

    private JdbcDataSource newH2DataSource()
    {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:bak" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        return ds;
    }

    // ==================== 场景 1：zip 结构与 manifest 字段 / QRTZ 排除 ====================

    @Test
    void backup_zipStructureAndManifestFields_qrtzExcluded() throws Exception
    {
        createSchema(rawDataSource);
        insertUserData(rawDataSource);
        createProfileFile("upload/2026/a.txt");
        createProfileFile("avatar/b.png");
        createProfileFile("import/c.csv");

        File zip = service.backup();

        assertTrue(zip.exists());
        assertEquals(backupDir.resolve("backup.zip").toFile(), zip);
        Map<String, byte[]> entries = readZip(zip);

        // schema/data 每表一文件 + manifest + 三目录入包
        assertTrue(entries.containsKey("manifest.json"));
        assertTrue(entries.containsKey("schema/" + T_SYS_USER + ".sql"));
        assertTrue(entries.containsKey("schema/" + T_NOTE_NOTE + ".sql"));
        assertTrue(entries.containsKey("data/" + T_SYS_USER + ".sql"));
        assertTrue(entries.containsKey("data/" + T_NOTE_NOTE + ".sql"));
        assertTrue(entries.containsKey("files/upload/2026/a.txt"));
        assertTrue(entries.containsKey("files/avatar/b.png"));
        assertTrue(entries.containsKey("files/import/c.csv"));

        // QRTZ_ 表不在包内
        for (String name : entries.keySet())
        {
            assertFalse(name.toUpperCase().contains("QRTZ"), "QRTZ 表不应入包: " + name);
        }

        // schema 文件：DROP + CREATE 原文
        String schemaSql = new String(entries.get("schema/" + T_SYS_USER + ".sql"), StandardCharsets.UTF_8);
        assertTrue(schemaSql.contains("DROP TABLE IF EXISTS `" + T_SYS_USER + "`"));
        assertTrue(schemaSql.contains("CREATE"));

        // manifest 字段齐全
        JSONObject manifest = JSONObject.parseObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        assertEquals(1, manifest.getIntValue("packageVersion"));
        String fingerprint = manifest.getString("schemaFingerprint");
        assertNotNull(fingerprint);
        assertEquals(64, fingerprint.length());
        assertNotNull(manifest.getString("createdAt"));
        assertEquals(2, manifest.getJSONArray("tables").size());
        // 表清单按表名排序：NOTE_NOTE 在前
        JSONObject noteNoteStat = manifest.getJSONArray("tables").getJSONObject(0);
        assertEquals(T_NOTE_NOTE, noteNoteStat.getString("name"));
        assertEquals(0, noteNoteStat.getIntValue("rows"));
        JSONObject sysUserStat = manifest.getJSONArray("tables").getJSONObject(1);
        assertEquals(T_SYS_USER, sysUserStat.getString("name"));
        assertEquals(2, sysUserStat.getIntValue("rows"));
        JSONObject fileStats = manifest.getJSONObject("fileStats");
        assertEquals(3, fileStats.getIntValue("totalFiles"));
        assertTrue(fileStats.getLongValue("totalBytes") > 0);
    }

    // ==================== 场景 2：特殊值转义往返 ====================

    @Test
    void backup_specialValues_roundTripPreserved() throws Exception
    {
        createSchema(rawDataSource);
        insertUserData(rawDataSource);

        Map<String, byte[]> entries = readZip(service.backup());

        // 转义形态文本断言（生成侧契约：单引号→''、反斜杠→\\、二进制→0x hex、NULL）
        String dataSql = new String(entries.get("data/" + T_SYS_USER + ".sql"), StandardCharsets.UTF_8);
        assertTrue(dataSql.startsWith("INSERT INTO `" + T_SYS_USER + "`(`USER_ID`,`USER_NAME`,`PASSWORD`,`REMARK`,`AVATAR`,`LAST_LOGIN`) VALUES("),
                "逐行 INSERT 应显式列出列名: " + dataSql.substring(0, Math.min(120, dataSql.length())));
        assertTrue(dataSql.contains("admin''s \"quote\""), "单引号应转义为 ''");
        assertTrue(dataSql.contains("back\\\\slash\\\\n path"), "反斜杠应转义为 \\\\");
        assertTrue(dataSql.contains("中文备注——含''引号''"), "中文与引号应正确转义");
        assertTrue(dataSql.contains("0x0AFF00"), "二进制列应编码为 0x hex");
        assertTrue(dataSql.contains("NULL"), "NULL 值应输出 NULL 字面量");

        // 导出再导入后逐值一致：新 H2 库执行包内 schema + data
        JdbcDataSource restoreDs = newH2DataSource();
        try (Connection conn = new H2ShowCreateDataSource(restoreDs).getConnection())
        {
            for (Map.Entry<String, byte[]> e : entries.entrySet())
            {
                if (e.getKey().startsWith("schema/"))
                {
                    executeSqlFile(conn, e.getValue());
                }
            }
            for (Map.Entry<String, byte[]> e : entries.entrySet())
            {
                if (e.getKey().startsWith("data/"))
                {
                    executeSqlFile(conn, e.getValue());
                }
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT USER_ID, USER_NAME, PASSWORD, REMARK, AVATAR, LAST_LOGIN FROM SYS_USER ORDER BY USER_ID"))
            {
                assertTrue(rs.next());
                assertEquals(1L, rs.getLong(1));
                assertEquals("admin's \"quote\"", rs.getString(2));
                // H2 无 backslash 转义（MySQL 专有语义）：'\\' 读回为字面双反斜杠。
                // 反斜杠转义正确性由上方文本断言保证（导出含 \\），此处验证无过度转义（'\\\\' 会读回四字符）
                assertEquals("back\\\\slash\\\\n path", rs.getString(3));
                assertEquals("中文备注——含'引号'", rs.getString(4));
                assertArrayEquals(new byte[] {0x0A, (byte) 0xFF, 0x00}, rs.getBytes(5));
                assertEquals(Timestamp.valueOf("2026-10-20 12:34:56.789"), rs.getTimestamp(6));

                assertTrue(rs.next());
                assertEquals(2L, rs.getLong(1));
                assertEquals("user2", rs.getString(2));
                assertNull(rs.getString(3));
                assertNull(rs.getString(4));
                assertNull(rs.getBytes(5));
                assertNull(rs.getTimestamp(6));

                assertFalse(rs.next());
            }
        }
    }

    // ==================== 场景 3：指纹稳定性与增删列变化 ====================

    @Test
    void backup_fingerprint_stableUntilSchemaChanges() throws Exception
    {
        createSchema(rawDataSource);
        insertUserData(rawDataSource);

        String fingerprint1 = readFingerprint(service.backup());
        String fingerprint2 = readFingerprint(service.backup());
        assertEquals(fingerprint1, fingerprint2, "同一库两次备份指纹应相同");

        // 加列后指纹变化
        try (Connection conn = rawDataSource.getConnection(); Statement st = conn.createStatement())
        {
            st.execute("ALTER TABLE sys_user ADD COLUMN extra_col VARCHAR(50)");
        }
        String fingerprint3 = readFingerprint(service.backup());
        assertNotEquals(fingerprint1, fingerprint3, "增删列后指纹应变化");
    }

    // ==================== 场景 4：空表导出 ====================

    @Test
    void backup_emptyTable_schemaPresentDataEmptyRowsZero() throws Exception
    {
        createSchema(rawDataSource);

        Map<String, byte[]> entries = readZip(service.backup());

        // 结构文件有、数据文件存在但为空、manifest 行数 0
        String schemaSql = new String(entries.get("schema/" + T_NOTE_NOTE + ".sql"), StandardCharsets.UTF_8);
        assertTrue(schemaSql.contains("CREATE"));
        assertEquals(0, entries.get("data/" + T_NOTE_NOTE + ".sql").length, "空表数据文件应为空");

        JSONObject manifest = JSONObject.parseObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        JSONObject noteNoteStat = manifest.getJSONArray("tables").getJSONObject(0);
        assertEquals(T_NOTE_NOTE, noteNoteStat.getString("name"));
        assertEquals(0, noteNoteStat.getIntValue("rows"));
    }

    // ==================== 场景 5：download 目录与 backupPath 不入包 ====================

    @Test
    void backup_downloadDirAndBackupPathExcluded() throws Exception
    {
        createSchema(rawDataSource);
        createProfileFile("upload/keep.txt");
        createProfileFile("download/leak.txt");

        Map<String, byte[]> entries = readZip(service.backup());

        assertTrue(entries.containsKey("files/upload/keep.txt"));
        for (String name : entries.keySet())
        {
            assertFalse(name.contains("download"), "download 目录不应入包: " + name);
            assertFalse(name.endsWith(".tmp"), "备份槽位临时文件不应入包: " + name);
        }
        // 备份槽位只保留最近 1 份 backup.zip，无历史遗留
        try (Stream<Path> list = Files.list(backupDir))
        {
            List<Path> files = new ArrayList<>();
            list.forEach(files::add);
            assertEquals(1, files.size(), "backupPath 下应只有 1 个备份包: " + files);
        }
    }

    // ==================== 场景 6：备份互斥拒绝 ====================

    @Test
    void backup_whileLocked_secondBackupRejected() throws Exception
    {
        createSchema(rawDataSource);

        assertTrue(BackupRestoreServiceImpl.tryAcquireBackupLock());
        ServiceException ex = assertThrows(ServiceException.class, () -> service.backup());
        assertEquals("系统备份/还原进行中，请稍后", ex.getMessage());
        assertFalse(Files.exists(backupDir.resolve("backup.zip")), "被拒的备份不应产出文件");

        // 锁释放后放行
        BackupRestoreServiceImpl.releaseLock();
        File zip = service.backup();
        assertTrue(zip.exists());
    }

    // ==================== 场景 7：消费者检查拒绝与放行 ====================

    @Test
    void consumerChecks_lockedRejected_unlockedPassThrough() throws Exception
    {
        createSchema(rawDataSource);
        assertTrue(BackupRestoreServiceImpl.tryAcquireBackupLock());
        try
        {
            // SQL 导入（UI/agent 双入口）
            NoteDwtableImportServiceImpl sqlImport = new NoteDwtableImportServiceImpl();
            List<ParsedInsert> parsedList = new ArrayList<>();
            ServiceException importDataEx = assertThrows(ServiceException.class,
                    () -> sqlImport.importData(50L, 60L, parsedList, 70L));
            assertEquals("系统备份/还原进行中，请稍后", importDataEx.getMessage());
            ServiceException importSqlEx = assertThrows(ServiceException.class,
                    () -> sqlImport.importSql(50L, 60L, "INSERT INTO t VALUES(1)", 70L));
            assertEquals("系统备份/还原进行中，请稍后", importSqlEx.getMessage());

            // Excel 导入（UI/agent 双入口；锁检查在方法首行，不触依赖字段）
            NoteDwtableExcelImportServiceImpl excelImport = new NoteDwtableExcelImportServiceImpl();
            ServiceException importExcelEx = assertThrows(ServiceException.class,
                    () -> excelImport.importExcel(50L, 60L, "/profile/upload/x.xlsx", null, 70L));
            assertEquals("系统备份/还原进行中，请稍后", importExcelEx.getMessage());
            ServiceException importExcelDataEx = assertThrows(ServiceException.class,
                    () -> excelImport.importExcelData(50L, 60L, null, "{}", 70L));
            assertEquals("系统备份/还原进行中，请稍后", importExcelDataEx.getMessage());

            // removeAllData / removeAll 清空入口
            NoteNoteServiceImpl noteService = new NoteNoteServiceImpl();
            ServiceException removeAllDataEx = assertThrows(ServiceException.class,
                    () -> noteService.removeAllData());
            assertEquals("系统备份/还原进行中，请稍后", removeAllDataEx.getMessage());
            ServiceException removeAllEx = assertThrows(ServiceException.class,
                    () -> noteService.removeAll());
            assertEquals("系统备份/还原进行中，请稍后", removeAllEx.getMessage());
        }
        finally
        {
            BackupRestoreServiceImpl.releaseLock();
        }

        // 锁释放后放行：越过锁检查进入各方法自身的参数校验/鉴权逻辑
        NoteDwtableImportServiceImpl sqlImport = new NoteDwtableImportServiceImpl();
        ServiceException passThrough = assertThrows(ServiceException.class,
                () -> sqlImport.importData(50L, null, new ArrayList<>(), 70L));
        assertEquals("导入失败：目标多维表格 id 为空", passThrough.getMessage());

        NoteNoteServiceImpl noteService = new NoteNoteServiceImpl();
        ServiceException removeAllPass = assertThrows(ServiceException.class,
                () -> noteService.removeAllData());
        assertNotEquals("系统备份/还原进行中，请稍后", removeAllPass.getMessage(),
                "锁释放后应越过锁检查（无登录上下文时走到管理员校验抛获取用户异常）");
    }

    // ==================== 测试辅助 ====================

    /** 建测试库结构：业务表 + 空表 + QRTZ 前缀表（KTD1 排除验证） */
    private void createSchema(JdbcDataSource ds) throws SQLException
    {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement())
        {
            st.execute("CREATE TABLE sys_user (user_id BIGINT PRIMARY KEY, user_name VARCHAR(100), "
                    + "password VARCHAR(100), remark VARCHAR(500), avatar VARBINARY(255), last_login TIMESTAMP)");
            st.execute("CREATE TABLE note_note (id BIGINT PRIMARY KEY, title VARCHAR(200))");
            st.execute("CREATE TABLE QRTZ_JOB_DETAILS (sched_name VARCHAR(120), job_name VARCHAR(200))");
        }
    }

    /** 插入特殊值数据：单引号/反斜杠/中文/NULL/二进制/时间戳，两行 */
    private void insertUserData(JdbcDataSource ds) throws SQLException
    {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO sys_user (user_id, user_name, password, remark, avatar, last_login) VALUES (?,?,?,?,?,?)"))
        {
            ps.setLong(1, 1L);
            ps.setString(2, "admin's \"quote\"");
            ps.setString(3, "back\\slash\\n path");
            ps.setString(4, "中文备注——含'引号'");
            ps.setBytes(5, new byte[] {0x0A, (byte) 0xFF, 0x00});
            ps.setTimestamp(6, Timestamp.valueOf("2026-10-20 12:34:56.789"));
            ps.executeUpdate();

            ps.setLong(1, 2L);
            ps.setString(2, "user2");
            ps.setNull(3, Types.VARCHAR);
            ps.setNull(4, Types.VARCHAR);
            ps.setNull(5, Types.VARBINARY);
            ps.setNull(6, Types.TIMESTAMP);
            ps.executeUpdate();
        }
    }

    /** 在 profile 下创建用户文件 */
    private Path createProfileFile(String relative) throws IOException
    {
        Path file = profileDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, relative.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** 读 zip 全部 entry 为 name→bytes */
    private Map<String, byte[]> readZip(File zip) throws IOException
    {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip.toPath())))
        {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null)
            {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buffer)) >= 0)
                {
                    bos.write(buffer, 0, n);
                }
                entries.put(entry.getName(), bos.toByteArray());
            }
        }
        return entries;
    }

    private String readFingerprint(File zip) throws IOException
    {
        JSONObject manifest = JSONObject.parseObject(
                new String(readZip(zip).get("manifest.json"), StandardCharsets.UTF_8));
        return manifest.getString("schemaFingerprint");
    }

    /** H2 不支持 MySQL 0x 十六进制字面量（仅 X'..' 标准语法），测试执行端做等价转换 */
    private static final Pattern HEX_LITERAL = Pattern.compile("0X([0-9A-F]+)");

    /**
     * 逐语句执行包内 SQL 文件：极简切分器（单引号字符串 '' 与 \x 转义、反引号标识符感知，
     * 字符串外分号切分）——生成物格式受控，无需 U2 的完整白名单状态机。
     */
    private void executeSqlFile(Connection conn, byte[] sqlBytes) throws SQLException
    {
        String sql = new String(sqlBytes, StandardCharsets.UTF_8);
        for (String statement : splitStatements(sql))
        {
            Matcher matcher = HEX_LITERAL.matcher(statement);
            StringBuffer converted = new StringBuffer();
            while (matcher.find())
            {
                matcher.appendReplacement(converted, "X'" + matcher.group(1) + "'");
            }
            matcher.appendTail(converted);
            try (Statement st = conn.createStatement())
            {
                st.execute(converted.toString());
            }
        }
    }

    private static List<String> splitStatements(String sql)
    {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean inIdentifier = false;
        for (int i = 0; i < sql.length(); i++)
        {
            char c = sql.charAt(i);
            current.append(c);
            if (inString)
            {
                if (c == '\\')
                {
                    // 反斜杠转义：原样保留两字符
                    if (i + 1 < sql.length())
                    {
                        current.append(sql.charAt(i + 1));
                        i++;
                    }
                }
                else if (c == '\'')
                {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'')
                    {
                        // '' 转义：原样保留
                        current.append('\'');
                        i++;
                    }
                    else
                    {
                        inString = false;
                    }
                }
            }
            else if (inIdentifier)
            {
                if (c == '`')
                {
                    inIdentifier = false;
                }
            }
            else if (c == '\'')
            {
                inString = true;
            }
            else if (c == '`')
            {
                inIdentifier = true;
            }
            else if (c == ';')
            {
                String statement = current.toString().trim();
                if (!statement.isEmpty())
                {
                    statements.add(statement);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty())
        {
            statements.add(tail);
        }
        return statements;
    }

    /**
     * H2 兼容包装数据源（仅测试用）：H2 无 SHOW CREATE TABLE 语法（MySQL 专有），
     * 把该语句翻译为 H2 的 SCRIPT NODATA TABLE 等价物，生产代码保持 MySQL 语义。
     */
    private static final class H2ShowCreateDataSource implements DataSource
    {
        private final JdbcDataSource target;

        H2ShowCreateDataSource(JdbcDataSource target)
        {
            this.target = target;
        }

        @Override
        public Connection getConnection() throws SQLException
        {
            return wrapConnection(target.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException
        {
            return wrapConnection(target.getConnection(username, password));
        }

        private static Connection wrapConnection(Connection real)
        {
            return (Connection) Proxy.newProxyInstance(H2ShowCreateDataSource.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if ("createStatement".equals(method.getName()) && (args == null || args.length == 0))
                        {
                            Statement realStatement = (Statement) method.invoke(real, args);
                            return wrapStatement(realStatement);
                        }
                        return method.invoke(real, args);
                    });
        }

        private static Statement wrapStatement(Statement real)
        {
            return (Statement) Proxy.newProxyInstance(H2ShowCreateDataSource.class.getClassLoader(),
                    new Class<?>[] {Statement.class}, (proxy, method, args) -> {
                        if ("executeQuery".equals(method.getName()) && args != null && args.length == 1
                                && args[0] instanceof String
                                && ((String) args[0]).startsWith("SHOW CREATE TABLE"))
                        {
                            return translateShowCreate(real, (String) args[0]);
                        }
                        return method.invoke(real, args);
                    });
        }

        /**
         * SHOW CREATE TABLE `t` → SCRIPT NODATA TABLE "T"（H2 等价 DDL，
         * 返回单列单行结果集对齐 MySQL SHOW CREATE 的取值方式）
         */
        private static ResultSet translateShowCreate(Statement real, String showSql) throws SQLException
        {
            String table = showSql.substring("SHOW CREATE TABLE `".length()).replace("`", "");
            StringBuilder ddl = new StringBuilder();
            try (ResultSet rs = real.executeQuery("SCRIPT NODATA TABLE \"" + table + "\""))
            {
                while (rs.next())
                {
                    String line = rs.getString(1);
                    if (line == null || line.trim().isEmpty())
                    {
                        continue;
                    }
                    if (ddl.length() > 0)
                    {
                        ddl.append("\n");
                    }
                    ddl.append(line);
                }
            }
            SimpleResultSet result = new SimpleResultSet();
            result.addColumn("Create Table", Types.VARCHAR, 8192, 0);
            result.addRow(ddl.toString());
            return result;
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException
        {
            return target.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException
        {
            return target.isWrapperFor(iface);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException
        {
            return target.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException
        {
            target.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException
        {
            target.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException
        {
            return target.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException
        {
            return target.getParentLogger();
        }
    }
}
