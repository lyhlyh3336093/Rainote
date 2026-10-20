package com.ruoyi.system.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.SimpleResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.dto.ParsedInsert;
import com.ruoyi.system.domain.dto.RestoreResult;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysDictTypeService;
import com.ruoyi.system.service.RestoreSchedulerHook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BackupRestoreServiceImpl} 备份/还厢单元测试（U1 场景 1-7 + U2 场景 1-16）。
 * <p>
 * 导出/还原逻辑用真实 H2 内存库（MySQL 兼容模式）跑真导出真还原。H2 无 SHOW CREATE TABLE
 * 语法（MySQL 专有），测试经 {@link H2ShowCreateDataSource} 把该语句翻译为 H2
 * 的 SCRIPT NODATA TABLE 等价物并<strong>改写为反引号形态</strong>（对齐生产 MySQL
 * SHOW CREATE 输出——U2 白名单要求反引号标识符），生产代码保持 MySQL 语义不变；
 * 写锁互斥与消费者检查用直接实例化 + 静态锁状态控制验证（锁检查在各方法首行，
 * 不触依赖字段）。
 * <p>
 * 还原失败路径经 {@link FailingDataSource} 在指定表语句处注入 SQLException
 * （一次性/恒挂两种策略分别覆盖"回放成功"与"回放自身失败"）；快照阶段失败经
 * {@link SnapshotFailDataSource} 拦截 SHOW CREATE TABLE 注入。
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
        // 复位三目录不可用标志与安全上下文，防止 U2 测试间泄漏
        BackupRestoreServiceImpl.resetFileDirsUnavailable();
        SecurityContextHolder.clearContext();
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

    // ==================== U2 场景 1：正常还原（Covers AE1） ====================

    @Test
    void restore_normal_allTablesRestoredToBackupPoint() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        writeProfileFile("upload/old-a.txt", "old-a");
        writeProfileFile("avatar/p.png", "avatar-p");
        byte[] packageBytes = backupToBytes();
        Map<String, List<List<String>>> backupPoint = dumpAll(rawDataSource);

        tamperCurrentData(rawDataSource);
        writeProfileFile("upload/extra-b.txt", "extra-b");

        RestoreResult result = service.restore(toUpload(packageBytes), true);

        // 全表逐值回到备份时点（含 note_note / note_dwtable_item 关键表；QRTZ 表不动）
        assertEquals(backupPoint, dumpAll(rawDataSource));
        // 文件全量替换：包内文件回来，篡改期的文件消失
        assertTrue(Files.exists(profileDir.resolve("upload/old-a.txt")));
        assertFalse(Files.exists(profileDir.resolve("upload/extra-b.txt")));
        assertEquals("old-a", new String(Files.readAllBytes(profileDir.resolve("upload/old-a.txt"))));

        assertEquals(3, result.getTableCount());
        assertEquals(6, result.getRecordCount());
        assertEquals(2, result.getFileCount());
        assertEquals(2, result.getUserCount());
        assertTrue(result.isCredentialChanged(), "包内凭据与现库（篡改后）应检出差异");
        assertTrue(result.isExecuted());
    }

    // ==================== U2 场景 2：全新环境还原（Covers AE1 字面旗舰场景） ====================

    @Test
    void restore_freshEnvironment_recreatesProfileDirs() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        writeProfileFile("upload/fresh/x.txt", "x-content");
        writeProfileFile("avatar/y.png", "y-content");
        writeProfileFile("import/z.csv", "z-content");
        byte[] packageBytes = backupToBytes();
        Map<String, List<List<String>>> backupPoint = dumpAll(rawDataSource);

        tamperCurrentData(rawDataSource);
        // 模拟全新环境：目标 profile 三目录均不存在
        for (String dir : new String[] {"upload", "avatar", "import"})
        {
            deleteRecursively(profileDir.resolve(dir));
        }

        service.restore(toUpload(packageBytes), true);

        // 目录被创建、逐文件与包一致；库回到备份时点
        assertEquals("x-content", new String(Files.readAllBytes(profileDir.resolve("upload/fresh/x.txt"))));
        assertEquals("y-content", new String(Files.readAllBytes(profileDir.resolve("avatar/y.png"))));
        assertEquals("z-content", new String(Files.readAllBytes(profileDir.resolve("import/z.csv"))));
        assertEquals(backupPoint, dumpAll(rawDataSource));
    }

    // ==================== U2 场景 3：中途失败回放（特征测试，Covers AE2） ====================

    @Test
    void restore_midFailure_replaysToPreRestoreState() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        writeProfileFile("upload/old-a.txt", "old-a");
        byte[] packageBytes = backupToBytes();

        // 还原前状态 Y：篡改库 + 文件
        tamperCurrentData(rawDataSource);
        Files.deleteIfExists(profileDir.resolve("upload/old-a.txt"));
        writeProfileFile("upload/new-b.txt", "new-b");
        Map<String, List<List<String>>> preRestore = dumpAll(rawDataSource);

        // 注入：还原执行到 SYS_USER 表（第 3 张）的首条语句时一次性失败——
        // 此时 NOTE_DWTABLE_ITEM / NOTE_NOTE 已被改写，回放必须把全部表拉回还原前
        BackupRestoreServiceImpl failing = buildRestoreService(
                new FailingDataSource(rawDataSource, "SYS_USER", true));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> failing.restore(toUpload(packageBytes), true));
        assertTrue(ex.getMessage().startsWith("还原失败：已回滚至还原前状态"),
                "失败应回放快照后返回回滚文案: " + ex.getMessage());

        // 全表逐值回到还原前状态（快照回放，不是 DB 事务回滚）；QRTZ 未被 DROP
        assertEquals(preRestore, dumpAll(rawDataSource));
        // 文件回到还原前（执行阶段失败在 swap 之前，未触碰文件）
        assertTrue(Files.exists(profileDir.resolve("upload/new-b.txt")));
        assertFalse(Files.exists(profileDir.resolve("upload/old-a.txt")));
        // 失败路径 finally：Quartz 恢复 + 锁释放 + 解压临时目录清理
        Mockito.verify(lastHook).pause();
        Mockito.verify(lastHook, Mockito.never()).reload();
        Mockito.verify(lastHook).resume();
        assertFalse(BackupRestoreServiceImpl.isWriteLocked());
        assertTmpDirCleaned();
    }

    // ==================== U2 场景 4：指纹拒绝零写入 ====================

    @Test
    void restore_fingerprintMismatch_rejectedZeroWrite() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        byte[] packageBytes = backupToBytes();

        // 现库结构变更后指纹必不匹配
        try (Connection conn = rawDataSource.getConnection(); Statement st = conn.createStatement())
        {
            st.execute("ALTER TABLE sys_user ADD COLUMN extra_col VARCHAR(50)");
        }
        Map<String, List<List<String>>> afterAlter = dumpAll(rawDataSource);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.restore(toUpload(packageBytes), true));
        assertTrue(ex.getMessage().contains("schema 指纹校验失败"), ex.getMessage());

        // 零写入：数据未动、未生成快照、未触碰文件
        assertEquals(afterAlter, dumpAll(rawDataSource));
        assertFalse(Files.exists(backupDir.resolve("snapshot.zip")));
        assertFalse(Files.exists(profileDir.resolve("upload")));
    }

    // ==================== U2 场景 5：SQL 白名单（词法级） ====================

    @Test
    void restore_maliciousSql_whitelistRejectedZeroWrite() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        byte[] packageBytes = backupToBytes();
        Map<String, byte[]> base = zipEntries(packageBytes);
        byte[] baseData = base.get("data/" + T_SYS_USER + ".sql");
        Map<String, List<List<String>>> before = dumpAll(rawDataSource);

        String[] malicious = {
            // 非白名单语句形态
            "CREATE USER 'hacker'@'%' IDENTIFIED BY 'pw'",
            "GRANT ALL ON *.* TO 'hacker'@'%'",
            "SELECT 'x' INTO OUTFILE '/tmp/pwn'",
            "DROP TABLE `SYS_USER`; TRUNCATE TABLE `NOTE_NOTE`",
            // INSERT...SELECT / 函数值（外带数据/时间盲注）
            "INSERT INTO `SYS_USER`(`USER_ID`) SELECT 1",
            "INSERT INTO `SYS_USER`(`USER_ID`) VALUES(LOAD_FILE('/etc/passwd'))",
            "INSERT INTO `SYS_USER`(`USER_ID`) VALUES(SLEEP(5))",
            // 可执行注释 /*!..*/ 展开为内容：藏进去的语句照样被白名单拒绝
            "INSERT INTO `SYS_USER`(`USER_ID`) VALUES('a'); /*! TRUNCATE TABLE `NOTE_NOTE` */",
            // -- 后未跟空白：朴素切分器当注释，MySQL 语义是两个减号——fail-closed 拒绝
            "INSERT INTO `SYS_USER`(`USER_ID`) VALUES('a'); --hidden",
            // 表不在 manifest 清单（QRTZ 前缀表被 U1 排除）
            "DROP TABLE `QRTZ_JOB_DETAILS`",
        };
        for (String sql : malicious)
        {
            Map<String, byte[]> entries = new LinkedHashMap<>(base);
            entries.put("data/" + T_SYS_USER + ".sql",
                    concat(baseData, ("\n" + sql + "\n").getBytes(StandardCharsets.UTF_8)));
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> service.restore(toUpload(buildZip(entries)), true), "应整体拒绝: " + sql);
            assertTrue(ex.getMessage().contains("SQL白名单校验") || ex.getMessage().contains("表不在备份包清单内"),
                    sql + " -> " + ex.getMessage());
            // 整体拒绝零写入
            assertEquals(before, dumpAll(rawDataSource), "恶意语句不得产生任何写入: " + sql);
        }
    }

    @Test
    void sanitizer_legalStatements_renderedCanonicalForm() throws Exception
    {
        // 合法语句（表清单内 DROP/CREATE/逐行字面量 INSERT）放行且以重渲染形态执行：
        // 解析产物重渲染（空格归一/字符串重新转义），不透传原文
        List<RestoreSqlSanitizer.ParsedStatement> statements = new ArrayList<>();
        RestoreSqlSanitizer.parseAndConsume("t", new StringReader(
                "DROP TABLE IF EXISTS `T1`;\n"
                + "CREATE TABLE `T1` (  `A` BIGINT NOT NULL , `B` VARCHAR(10) DEFAULT 'x' ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n"
                + "INSERT INTO `T1`(`A`,`B`)   VALUES( 'a;b' , -5 );\n"),
                java.util.Collections.singleton("t1"), statements::add);
        assertEquals(3, statements.size());
        assertEquals("DROP TABLE IF EXISTS `T1`", statements.get(0).render());
        assertEquals("CREATE TABLE `T1` (`A` BIGINT NOT NULL , `B` VARCHAR ( 10 ) DEFAULT 'x')"
                + " ENGINE = InnoDB DEFAULT CHARSET = utf8mb4", statements.get(1).render());
        assertEquals("INSERT INTO `T1`(`A`,`B`) VALUES('a;b',-5)", statements.get(2).render());

        // 注释语义：# 与 -- 空白注释丢弃；/*!..*/ 可执行注释展开为内容（合法内容放行）
        List<RestoreSqlSanitizer.ParsedStatement> execStatements = new ArrayList<>();
        RestoreSqlSanitizer.parseAndConsume("t", new StringReader(
                "INSERT INTO `T1`(`A`) VALUES('a'); # trailing comment\n"
                + "-- space comment\n"
                + "/*! INSERT INTO `T1`(`A`) VALUES('b') */\n"),
                java.util.Collections.singleton("t1"), execStatements::add);
        assertEquals(2, execStatements.size());
        assertEquals("INSERT INTO `T1`(`A`) VALUES('b')", execStatements.get(1).render());

        // 字符串字面量内的 ; 与 '' 转义：解码后按统一规则重建
        List<RestoreSqlSanitizer.ParsedStatement> escaped = new ArrayList<>();
        RestoreSqlSanitizer.parseAndConsume("t", new StringReader(
                "INSERT INTO `T1`(`A`) VALUES('a''b;c');\n"),
                java.util.Collections.singleton("t1"), escaped::add);
        assertEquals("INSERT INTO `T1`(`A`) VALUES('a''b;c')", escaped.get(0).render());
    }

    // ==================== U2 场景 6：解压防护（在任何库写入前） ====================

    @Test
    void restore_zipGuard_traversalAndLimitsRejected() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        byte[] packageBytes = backupToBytes();
        Map<String, byte[]> base = zipEntries(packageBytes);
        Map<String, List<List<String>>> before = dumpAll(rawDataSource);

        // 路径穿越条目：整体拒绝
        Map<String, byte[]> traversal = new LinkedHashMap<>(base);
        traversal.put("../evil.txt", "x".getBytes(StandardCharsets.UTF_8));
        ServiceException traversalEx = assertThrows(ServiceException.class,
                () -> service.restore(toUpload(buildZip(traversal)), true));
        assertTrue(traversalEx.getMessage().contains("路径穿越"), traversalEx.getMessage());
        assertEquals(before, dumpAll(rawDataSource));

        // 条目数超限：拒绝（上限调到 2，正常包条目数远超）
        BackupRestoreServiceImpl limited = new BackupRestoreServiceImpl();
        ReflectionTestUtils.setField(limited, "dataSource", new H2ShowCreateDataSource(rawDataSource));
        ReflectionTestUtils.setField(limited, "maxExtractEntries", 2);
        ServiceException entriesEx = assertThrows(ServiceException.class,
                () -> limited.restore(toUpload(packageBytes), true));
        assertTrue(entriesEx.getMessage().contains("条目数超限"), entriesEx.getMessage());
        assertEquals(before, dumpAll(rawDataSource));

        // 总解压大小超限：拒绝
        ReflectionTestUtils.setField(limited, "maxExtractEntries", 50000);
        ReflectionTestUtils.setField(limited, "maxExtractBytes", 10L);
        ServiceException sizeEx = assertThrows(ServiceException.class,
                () -> limited.restore(toUpload(packageBytes), true));
        assertTrue(sizeEx.getMessage().contains("解压总量超限"), sizeEx.getMessage());
        assertEquals(before, dumpAll(rawDataSource));
        assertTmpDirCleaned();
    }

    // ==================== U2 场景 7：预览与 sys_user 告警（无 password 值） ====================

    @Test
    void preview_userCredentialDiff_reportedWithoutPasswordValues() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        byte[] packageBytes = backupToBytes();

        tamperCurrentData(rawDataSource);
        Map<String, List<List<String>>> tampered = dumpAll(rawDataSource);

        // 包（备份时点）与现库（篡改后）比对：仅计数与布尔告警
        RestoreResult preview = service.previewRestore(toUpload(packageBytes));
        assertFalse(preview.isExecuted());
        assertEquals(3, preview.getTableCount());
        assertEquals(6, preview.getRecordCount());
        assertEquals(2, preview.getUserCount());
        assertTrue(preview.isCredentialChanged(), "凭据差异应出现在预览结果");
        // password 值不进任何输出：结果序列化不含原密码也不含篡改密码
        String json = JSON.toJSONString(preview);
        assertFalse(json.contains("tampered-pwd"));
        assertFalse(json.contains("$s2a$hash1"));

        // 无差异时布尔为 false：篡改后重新备份，新包与现库一致（篡改后现库 2 个账号）
        byte[] freshPackage = backupToBytes();
        RestoreResult freshPreview = service.previewRestore(toUpload(freshPackage));
        assertFalse(freshPreview.isCredentialChanged());
        assertEquals(2, freshPreview.getUserCount(), "篡改后现库有 2 个账号，新包应与现库一致");
        assertEquals(tampered, dumpAll(rawDataSource), "预览不得写库");
    }

    // ==================== U2 场景 8：快照槽位独立（不撞锁、不顶替） ====================

    @Test
    void restore_snapshotSlot_separateFromBackupSlot() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        byte[] packageBytes = backupToBytes();

        tamperCurrentData(rawDataSource);
        service.restore(toUpload(packageBytes), true);

        // 快照已生成（restore 持锁期间经 exportInternal 完成——不撞自身写锁即本测试通过）
        assertTrue(Files.exists(backupDir.resolve("snapshot.zip")));
        // 快照 = 还原前状态（篡改后 Y 时点）；backup.zip = 原备份（D1 时点）未被顶替
        Map<String, byte[]> snapshot = zipEntries(Files.readAllBytes(backupDir.resolve("snapshot.zip")));
        Map<String, byte[]> backup = zipEntries(Files.readAllBytes(backupDir.resolve("backup.zip")));
        String snapshotData = new String(snapshot.get("data/" + T_SYS_USER + ".sql"), StandardCharsets.UTF_8);
        String backupData = new String(backup.get("data/" + T_SYS_USER + ".sql"), StandardCharsets.UTF_8);
        assertTrue(snapshotData.contains("tampered-pwd"), "快照应为还原前（篡改后）状态");
        assertTrue(backupData.contains("$s2a$hash1"));
        assertFalse(backupData.contains("tampered-pwd"), "常规备份槽位不得被快照顶替");
    }

    // ==================== U2 场景 9：会话判定（Covers AE3） ====================

    @Test
    void restore_sessionJudge_reloginByUserIdPresence() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        byte[] packageBytes = backupToBytes();

        // 包内 sys_user 含当前 userId=1 → 免重登
        loginAs(1L);
        RestoreResult stay = service.restore(toUpload(packageBytes), true);
        assertFalse(stay.isReloginRequired());

        // 构造不含 userId=1 的包（删除包内该行）：需重登
        Map<String, byte[]> entries = zipEntries(packageBytes);
        String dataSql = new String(entries.get("data/" + T_SYS_USER + ".sql"), StandardCharsets.UTF_8);
        StringBuilder filtered = new StringBuilder();
        for (String line : dataSql.split("\n"))
        {
            if (!line.contains("VALUES(1,"))
            {
                filtered.append(line).append('\n');
            }
        }
        entries.put("data/" + T_SYS_USER + ".sql", filtered.toString().getBytes(StandardCharsets.UTF_8));
        tamperCurrentData(rawDataSource);
        RestoreResult relogin = service.restore(toUpload(buildZip(entries)), true);
        assertTrue(relogin.isReloginRequired(), "包内不含当前 userId 应标记需重登");
    }

    // ==================== U2 场景 10：缓存与调度器重建（InOrder） ====================

    @Test
    void restore_success_cacheAndSchedulerReloadInOrder() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        byte[] packageBytes = backupToBytes();
        tamperCurrentData(rawDataSource);

        BackupRestoreServiceImpl restoreService = buildRestoreService(
                new H2ShowCreateDataSource(rawDataSource));
        restoreService.restore(toUpload(packageBytes), true);

        // 字典/参数缓存刷新 + 调度器 clear+重载（经 RestoreSchedulerHook），顺序：
        // pause → 字典重建 → 参数重建 → reload → finally resume
        InOrder inOrder = Mockito.inOrder(lastHook, lastDictService, lastConfigService);
        inOrder.verify(lastHook).pause();
        inOrder.verify(lastDictService).resetDictCache();
        inOrder.verify(lastConfigService).resetConfigCache();
        inOrder.verify(lastHook).reload();
        inOrder.verify(lastHook).resume();
    }

    // ==================== U2 场景 11：失败路径收尾 + 启动 staging 兜底 ====================

    @Test
    void restore_failurePath_cleanupAndStartupRecovery() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        writeProfileFile("upload/old-a.txt", "old-a");
        byte[] packageBytes = backupToBytes();
        tamperCurrentData(rawDataSource);

        BackupRestoreServiceImpl failing = buildRestoreService(
                new FailingDataSource(rawDataSource, "SYS_USER", true));
        assertThrows(ServiceException.class, () -> failing.restore(toUpload(packageBytes), true));
        Mockito.verify(lastHook).pause();
        Mockito.verify(lastHook).resume();
        assertFalse(BackupRestoreServiceImpl.isWriteLocked());

        // 启动清理：>24h 的解压临时目录删除、新鲜目录保留
        Path tmpRoot = Files.createDirectories(backupDir.resolve("tmp"));
        Path staleDir = Files.createDirectories(tmpRoot.resolve("restore-stale"));
        Files.createDirectories(tmpRoot.resolve("restore-fresh"));
        Files.setLastModifiedTime(staleDir,
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(25 * 3600)));
        // 启动 staging 残留检测回移：上次进程异常退出留下的旧 upload 目录
        Path leftover = Files.createDirectories(backupDir.resolve("staging/swap-crash/upload"));
        Files.write(leftover.resolve("old-a.txt"), "old-a".getBytes(StandardCharsets.UTF_8));
        service.startupCleanup();
        assertFalse(Files.exists(staleDir), "超 24h 的临时目录应被清理");
        assertTrue(Files.exists(tmpRoot.resolve("restore-fresh")), "新鲜临时目录应保留");
        assertEquals("old-a", new String(Files.readAllBytes(profileDir.resolve("upload/old-a.txt"))),
                "staging 残留应回移到 profile");
        assertFalse(Files.exists(backupDir.resolve("staging/swap-crash")), "回移完成的 swap 壳应清理");
    }

    // ==================== U2 场景 12：快照生成阶段失败（库未动） ====================

    @Test
    void restore_snapshotPhaseFailure_failsCleanWithoutReplay() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        writeProfileFile("upload/old-a.txt", "old-a");
        byte[] packageBytes = backupToBytes();
        tamperCurrentData(rawDataSource);
        Map<String, List<List<String>>> beforeFailure = dumpAll(rawDataSource);

        BackupRestoreServiceImpl failing = buildRestoreService(new SnapshotFailDataSource(rawDataSource));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> failing.restore(toUpload(packageBytes), true));
        // 快照生成阶段失败 → 直接失败返回（库未动、无回放）
        assertTrue(ex.getMessage().contains("备份失败"), "快照失败应直接返回导出失败: " + ex.getMessage());
        assertEquals(beforeFailure, dumpAll(rawDataSource), "库不得被触碰");
        assertFalse(Files.exists(backupDir.resolve("snapshot.zip")));
        assertTrue(Files.exists(profileDir.resolve("upload/old-a.txt")), "文件不得被触碰");
        Mockito.verify(lastHook).resume();
        assertFalse(BackupRestoreServiceImpl.isWriteLocked());
        assertTmpDirCleaned();
    }

    // ==================== U2 场景 13：回放自身失败（不递归，终止报不一致） ====================

    @Test
    void restore_replayFailure_terminatesAsInconsistent() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        byte[] packageBytes = backupToBytes();
        tamperCurrentData(rawDataSource);

        // 恒挂策略：执行阶段挂 SYS_USER，回放再执行到 SYS_USER 时再次挂 → 回放失败
        BackupRestoreServiceImpl failing = buildRestoreService(
                new FailingDataSource(rawDataSource, "SYS_USER", false));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> failing.restore(toUpload(packageBytes), true));
        assertTrue(ex.getMessage().contains("系统处于不一致状态"),
                "回放失败必须终止并报不一致（不静默不递归）: " + ex.getMessage());
        assertFalse(BackupRestoreServiceImpl.isWriteLocked());
        Mockito.verify(lastHook).resume();
    }

    // ==================== U2 场景 14：回放豁免指纹（KTD6 受限路径） ====================

    @Test
    void replaySnapshot_bypassesFingerprintCheck() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        byte[] packageBytes = backupToBytes();
        Map<String, List<List<String>>> backupPoint = dumpAll(rawDataSource);
        // 放置还原前快照（备份包即当时库的等价快照）
        Files.write(backupDir.resolve(BackupRestoreServiceImpl.SNAPSHOT_SLOT), packageBytes);

        // 库结构被改写后指纹必不匹配，但回放（受限内部路径）不做指纹校验
        try (Connection conn = rawDataSource.getConnection(); Statement st = conn.createStatement())
        {
            st.execute("ALTER TABLE sys_user ADD COLUMN extra_col VARCHAR(50)");
        }
        service.replaySnapshot();

        // 回放完成：结构回到快照时点（extra_col 消失）、数据逐值回快照时点
        assertEquals(backupPoint, dumpAll(rawDataSource));
    }

    // ==================== U2 场景 15：confirmed 重跑全部校验 ====================

    @Test
    void restore_confirmed_revalidatesUploadedPackage() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        byte[] packageBytes = backupToBytes();
        tamperCurrentData(rawDataSource);
        Map<String, List<List<String>>> before = dumpAll(rawDataSource);

        // 预览用包 A：包（备份时点）与篡改后现库存在差异，预览检出且不写库
        Map<String, List<List<String>>> beforePreview = dumpAll(rawDataSource);
        RestoreResult preview = service.previewRestore(toUpload(packageBytes));
        assertTrue(preview.isCredentialChanged(), "预览应检出包与篡改后现库的凭据差异");
        assertEquals(beforePreview, dumpAll(rawDataSource), "预览不得写库");

        // confirm 阶段上传包 B（含恶意 SQL）：不信任预览结果，B 被完整校验拒绝
        Map<String, byte[]> evil = zipEntries(packageBytes);
        evil.put("data/" + T_SYS_USER + ".sql", concat(evil.get("data/" + T_SYS_USER + ".sql"),
                "\nGRANT ALL ON *.* TO 'hacker'@'%'\n".getBytes(StandardCharsets.UTF_8)));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.restore(toUpload(buildZip(evil)), true));
        assertTrue(ex.getMessage().contains("SQL白名单校验"), ex.getMessage());
        assertEquals(before, dumpAll(rawDataSource), "恶意包 B 不得产生任何写入");
    }

    // ==================== U2 场景 16：运行期回移失败（重试+告警+不可用标记） ====================

    @Test
    void restore_rollbackMoveFailure_marksDirsUnavailable() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        writeProfileFile("upload/old-a.txt", "old-a");
        byte[] packageBytes = backupToBytes();

        // 还原前状态：库篡改 + 文件篡改（回滚目标）
        tamperCurrentData(rawDataSource);
        Files.deleteIfExists(profileDir.resolve("upload/old-a.txt"));
        writeProfileFile("upload/before-fail.txt", "before-fail");
        Map<String, List<List<String>>> preRestore = dumpAll(rawDataSource);

        // 调度器重载失败（恶意包植入无效 cron 的等价注入）触发失败回放分支：
        // 库回放成功 + 文件回移注入恒挂 → 重试耗尽 → 告警 + 三目录不可用标记
        FlakyRollbackService flaky = buildFlakyRollbackService(
                new H2ShowCreateDataSource(rawDataSource));
        flaky.setFailFirstN(99);
        Mockito.doThrow(new RuntimeException("无效cron")).when(lastHook).reload();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(BackupRestoreServiceImpl.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try
        {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> flaky.restore(toUpload(packageBytes), true));
            assertTrue(ex.getMessage().startsWith("还原失败：已回滚至还原前状态"), ex.getMessage());
        }
        finally
        {
            logger.detachAppender(appender);
        }

        assertTrue(BackupRestoreServiceImpl.isFileDirsUnavailable(), "回移失败应置三目录不可用标记");
        assertTrue(appender.list.stream().anyMatch(event ->
                event.getLevel() == ch.qos.logback.classic.Level.ERROR
                        && event.getFormattedMessage().contains("不可用")), "应产出告警日志");
        // 库已回放到还原前；回移失败时旧数据保留在 staging 待人工回移（数据不丢失）
        assertEquals(preRestore, dumpAll(rawDataSource));
        assertTrue(existsFileUnder(backupDir.resolve("staging"), "before-fail.txt"),
                "staging 应保留待回移的旧目录文件");
        // 三目录不可用：后续还原文件替换被拒
        assertThrows(ServiceException.class, BackupRestoreServiceImpl::assertFileDirsAvailable);

        // 人工处理模拟：复位标志后重跑回移（注入放行）→ 旧目录回移成功、swap 壳清理
        flaky.setFailFirstN(0);
        BackupRestoreServiceImpl.resetFileDirsUnavailable();
        flaky.rollbackStaging();
        assertEquals("before-fail", new String(Files.readAllBytes(profileDir.resolve("upload/before-fail.txt"))),
                "回移完成后还原前的旧目录回到原位");
        assertFalse(Files.exists(profileDir.resolve("upload/old-a.txt")), "包内新目录被旧目录替换");
        try (Stream<Path> list = Files.list(backupDir.resolve("staging")))
        {
            assertEquals(0, list.count(), "回移完成的 swap 壳应清理");
        }
    }

    @Test
    void restore_rollbackRetriesThenSucceeds_dirsStayAvailable() throws Exception
    {
        createRestoreSchema(rawDataSource);
        insertRestoreData(rawDataSource);
        writeProfileFile("upload/old-a.txt", "old-a");
        byte[] packageBytes = backupToBytes();
        tamperCurrentData(rawDataSource);
        Files.deleteIfExists(profileDir.resolve("upload/old-a.txt"));
        writeProfileFile("upload/new-file.txt", "new-file");
        Map<String, List<List<String>>> preRestore = dumpAll(rawDataSource);

        // 回移注入前 2 次失败：第 3 次尝试成功（重试语义）
        FlakyRollbackService flaky = buildFlakyRollbackService(
                new H2ShowCreateDataSource(rawDataSource));
        flaky.setFailFirstN(2);
        Mockito.doThrow(new RuntimeException("无效cron")).when(lastHook).reload();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> flaky.restore(toUpload(packageBytes), true));
        assertTrue(ex.getMessage().startsWith("还原失败：已回滚至还原前状态"), ex.getMessage());

        // 重试成功路径：不置不可用标志，回移完成、库回到还原前
        assertFalse(BackupRestoreServiceImpl.isFileDirsUnavailable());
        assertEquals(3, flaky.getMoveCalls(), "应重试至第 3 次成功");
        assertEquals(preRestore, dumpAll(rawDataSource));
        assertEquals("new-file", new String(Files.readAllBytes(profileDir.resolve("upload/new-file.txt"))),
                "还原前旧目录应回到原位");
        assertFalse(Files.exists(profileDir.resolve("upload/old-a.txt")), "包内新目录被旧目录替换");
    }

    // ==================== 测试辅助 ====================

    /** 最近一次 buildRestoreService 注入的调度器 Hook mock（供各测试 verify） */
    private RestoreSchedulerHook lastHook;

    /** 最近一次注入的字典缓存服务 mock */
    private ISysDictTypeService lastDictService;

    /** 最近一次注入的参数缓存服务 mock */
    private ISysConfigService lastConfigService;

    /** U2 还原测试全表（H2 大写），dumpAll 顺序遍历 */
    private static final String[][] RESTORE_TABLES = {
        {"NOTE_DWTABLE_ITEM", "ID"}, {"NOTE_NOTE", "ID"}, {"QRTZ_JOB_DETAILS", "SCHED_NAME"},
        {"SYS_USER", "USER_ID"}
    };

    /** 构建 U2 还原测试 service（注入失败策略 DataSource 与调度/缓存联调 mock） */
    private BackupRestoreServiceImpl buildRestoreService(DataSource dataSource)
    {
        BackupRestoreServiceImpl restoreService = new BackupRestoreServiceImpl();
        ReflectionTestUtils.setField(restoreService, "dataSource", dataSource);
        lastHook = Mockito.mock(RestoreSchedulerHook.class);
        lastDictService = Mockito.mock(ISysDictTypeService.class);
        lastConfigService = Mockito.mock(ISysConfigService.class);
        ReflectionTestUtils.setField(restoreService, "schedulerHook", lastHook);
        ReflectionTestUtils.setField(restoreService, "dictTypeService", lastDictService);
        ReflectionTestUtils.setField(restoreService, "configService", lastConfigService);
        return restoreService;
    }

    /** 构建可注入回移失败的 service 子类（场景 16：覆盖 moveForRollback 前 N 次抛 IOException） */
    private FlakyRollbackService buildFlakyRollbackService(DataSource dataSource)
    {
        FlakyRollbackService flaky = new FlakyRollbackService();
        ReflectionTestUtils.setField(flaky, "dataSource", dataSource);
        lastHook = Mockito.mock(RestoreSchedulerHook.class);
        lastDictService = Mockito.mock(ISysDictTypeService.class);
        lastConfigService = Mockito.mock(ISysConfigService.class);
        ReflectionTestUtils.setField(flaky, "schedulerHook", lastHook);
        ReflectionTestUtils.setField(flaky, "dictTypeService", lastDictService);
        ReflectionTestUtils.setField(flaky, "configService", lastConfigService);
        return flaky;
    }

    /** 触发一次备份并返回包字节（H2 经翻译层导出） */
    private byte[] backupToBytes() throws Exception
    {
        File zip = service.backup();
        return Files.readAllBytes(zip.toPath());
    }

    /** 包字节 → 上传参数 */
    private MultipartFile toUpload(byte[] packageBytes)
    {
        return new MockMultipartFile("file", "backup.zip", "application/zip", packageBytes);
    }

    /** 包字节 → entry 映射 */
    private Map<String, byte[]> zipEntries(byte[] packageBytes) throws IOException
    {
        File tmp = Files.createTempFile(tempRoot, "pkg", ".zip").toFile();
        Files.write(tmp.toPath(), packageBytes);
        return readZip(tmp);
    }

    /** entry 映射 → 包字节 */
    private byte[] buildZip(Map<String, byte[]> entries) throws IOException
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos))
        {
            for (Map.Entry<String, byte[]> entry : entries.entrySet())
            {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b)
    {
        byte[] merged = new byte[a.length + b.length];
        System.arraycopy(a, 0, merged, 0, a.length);
        System.arraycopy(b, 0, merged, a.length, b.length);
        return merged;
    }

    /** 递归删除目录 */
    private static void deleteRecursively(Path dir) throws IOException
    {
        if (!Files.exists(dir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /** 断言 backupPath/tmp 无解压残留（请求结束 try-finally 已清理） */
    private void assertTmpDirCleaned() throws IOException
    {
        Path tmpRoot = backupDir.resolve("tmp");
        if (!Files.isDirectory(tmpRoot))
        {
            return;
        }
        try (Stream<Path> list = Files.list(tmpRoot))
        {
            List<Path> remain = new ArrayList<>();
            list.forEach(remain::add);
            assertEquals(0, remain.size(), "解压临时目录应全部清理: " + remain);
        }
    }

    /** 模拟登录上下文（会话判定测试） */
    private void loginAs(long userId)
    {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    /** 判断 root 目录树下是否存在指定文件名的文件 */
    private static boolean existsFileUnder(Path root, String fileName) throws IOException
    {
        if (!Files.isDirectory(root))
        {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root))
        {
            return walk.anyMatch(p -> p.getFileName() != null && fileName.equals(p.getFileName().toString()));
        }
    }

    /** 在 profile 下写用户文件（内容可控，区别于 U1 createProfileFile 以文件名为内容） */
    private Path writeProfileFile(String relative, String content) throws IOException
    {
        Path file = profileDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** 建 U2 还原测试库：业务表 + QRTZ 前缀表（结构对齐 U2 场景，避免二进制/hex 方言差异） */
    private void createRestoreSchema(JdbcDataSource ds) throws SQLException
    {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement())
        {
            st.execute("CREATE TABLE sys_user (user_id BIGINT PRIMARY KEY, user_name VARCHAR(100), "
                    + "password VARCHAR(100), remark VARCHAR(500), last_login TIMESTAMP)");
            st.execute("CREATE TABLE note_note (id BIGINT PRIMARY KEY, title VARCHAR(200))");
            st.execute("CREATE TABLE note_dwtable_item (id BIGINT PRIMARY KEY, note_id BIGINT, content VARCHAR(500))");
            st.execute("CREATE TABLE QRTZ_JOB_DETAILS (sched_name VARCHAR(120), job_name VARCHAR(200))");
        }
    }

    /** 插入 U2 测试数据（备份时点 D1）：2 用户 + 2 笔记 + 2 行表格记录 + 1 条 QRTZ 行 */
    private void insertRestoreData(JdbcDataSource ds) throws SQLException
    {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO sys_user (user_id, user_name, password, remark, last_login) VALUES (?,?,?,?,?)"))
        {
            ps.setLong(1, 1L);
            ps.setString(2, "admin");
            ps.setString(3, "$s2a$hash1");
            ps.setString(4, "管理员");
            ps.setTimestamp(5, Timestamp.valueOf("2026-10-20 08:00:00"));
            ps.executeUpdate();
            ps.setLong(1, 2L);
            ps.setString(2, "user2");
            ps.setString(3, "$s2a$hash2");
            ps.setString(4, null);
            ps.setTimestamp(5, null);
            ps.executeUpdate();
        }
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement())
        {
            st.execute("INSERT INTO note_note VALUES (101, '笔记A'), (102, '笔记B')");
            st.execute("INSERT INTO note_dwtable_item VALUES (1001, 101, '行1'), (1002, 102, '行2')");
            st.execute("INSERT INTO QRTZ_JOB_DETAILS VALUES ('sched', 'job1')");
        }
    }

    /** 篡改当前库与文件为"还原前状态 Y"（区别于备份时点 D1，回滚断言基准） */
    private void tamperCurrentData(JdbcDataSource ds) throws SQLException
    {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement())
        {
            st.execute("UPDATE sys_user SET password = 'tampered-pwd', remark = '篡改后' WHERE user_id = 1");
            st.execute("DELETE FROM sys_user WHERE user_id = 2");
            st.execute("INSERT INTO sys_user VALUES (3, 'user3', 'pw3', '新用户', NULL)");
            st.execute("UPDATE note_note SET title = '篡改标题' WHERE id = 101");
            st.execute("INSERT INTO note_note VALUES (103, '笔记C')");
            st.execute("DELETE FROM note_dwtable_item WHERE id = 1002");
            st.execute("INSERT INTO note_dwtable_item VALUES (1003, 103, '行3')");
        }
    }

    /** 全表逐值 dump（getString 归一，备份/还原前/后一致性断言基准） */
    private Map<String, List<List<String>>> dumpAll(JdbcDataSource ds) throws SQLException
    {
        Map<String, List<List<String>>> dump = new LinkedHashMap<>();
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement())
        {
            for (String[] table : RESTORE_TABLES)
            {
                List<List<String>> rows = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT * FROM " + table[0] + " ORDER BY " + table[1]))
                {
                    int columnCount = rs.getMetaData().getColumnCount();
                    while (rs.next())
                    {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= columnCount; i++)
                        {
                            row.add(rs.getString(i));
                        }
                        rows.add(row);
                    }
                }
                dump.put(table[0], rows);
            }
        }
        return dump;
    }

    /**
     * 还原执行失败注入器：拦截 Statement.execute(String)，命中 failMarker 的语句按策略抛
     * SQLException。oneShot=true 仅首次命中抛（覆盖"执行挂一次、回放成功"）；false 恒挂
     * （覆盖"回放自身失败"）。SHOW CREATE 翻译与 executeQuery/prepareStatement 路径不受
     * 影响（快照导出走 executeQuery），注入面即白名单执行面的 DDL 单发通道。
     */
    private static final class FailingDataSource extends H2ShowCreateDataSource
    {
        private final String failMarker;

        private final boolean oneShot;

        private boolean fired = false;

        FailingDataSource(JdbcDataSource target, String failMarker, boolean oneShot)
        {
            super(target);
            this.failMarker = failMarker;
            this.oneShot = oneShot;
        }

        @Override
        public Connection getConnection() throws SQLException
        {
            return wrapFailing(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException
        {
            return wrapFailing(super.getConnection(username, password));
        }

        private Connection wrapFailing(Connection translated) throws SQLException
        {
            return (Connection) Proxy.newProxyInstance(FailingDataSource.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if ("createStatement".equals(method.getName()) && (args == null || args.length == 0))
                        {
                            Statement statement = (Statement) method.invoke(translated, args);
                            return (Statement) Proxy.newProxyInstance(
                                    FailingDataSource.class.getClassLoader(), new Class<?>[] {Statement.class},
                                    (p2, m2, a2) -> {
                                        if ("execute".equals(m2.getName()) && a2 != null && a2.length >= 1
                                                && a2[0] instanceof String)
                                        {
                                            maybeFail((String) a2[0]);
                                        }
                                        return m2.invoke(statement, a2);
                                    });
                        }
                        return method.invoke(translated, args);
                    });
        }

        private void maybeFail(String sql) throws SQLException
        {
            if (!sql.contains(failMarker))
            {
                return;
            }
            if (oneShot && fired)
            {
                return;
            }
            fired = true;
            throw new SQLException("模拟还原执行失败: " + failMarker);
        }
    }

    /**
     * 快照生成阶段失败注入器：拦截 SHOW CREATE TABLE（exportInternal 首个动作），
     * 校验阶段（元数据/SELECT）不受影响——验证"快照失败→直接失败返回、库未动"。
     */
    private static final class SnapshotFailDataSource extends H2ShowCreateDataSource
    {
        SnapshotFailDataSource(JdbcDataSource target)
        {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException
        {
            return wrapFailing(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException
        {
            return wrapFailing(super.getConnection(username, password));
        }

        private Connection wrapFailing(Connection translated) throws SQLException
        {
            return (Connection) Proxy.newProxyInstance(SnapshotFailDataSource.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if ("createStatement".equals(method.getName()) && (args == null || args.length == 0))
                        {
                            Statement statement = (Statement) method.invoke(translated, args);
                            return (Statement) Proxy.newProxyInstance(
                                    SnapshotFailDataSource.class.getClassLoader(),
                                    new Class<?>[] {Statement.class}, (p2, m2, a2) -> {
                                        if ("executeQuery".equals(m2.getName()) && a2 != null && a2.length == 1
                                                && a2[0] instanceof String
                                                && ((String) a2[0]).startsWith("SHOW CREATE TABLE"))
                                        {
                                            throw new SQLException("模拟快照导出失败");
                                        }
                                        return m2.invoke(statement, a2);
                                    });
                        }
                        return method.invoke(translated, args);
                    });
        }
    }

    /** 回移失败注入子类：moveForRollback 前 failFirstN 次抛 IOException（场景 16 重试语义） */
    private static final class FlakyRollbackService extends BackupRestoreServiceImpl
    {
        private int failFirstN = 99;

        private int moveCalls = 0;

        void setFailFirstN(int failFirstN)
        {
            this.failFirstN = failFirstN;
        }

        int getMoveCalls()
        {
            return moveCalls;
        }

        @Override
        protected void moveForRollback(Path source, Path target) throws IOException
        {
            if (++moveCalls <= failFirstN)
            {
                throw new IOException("模拟回移 IO 失败");
            }
            super.moveForRollback(source, target);
        }
    }

    // ==================== U1 测试辅助（原有） ====================

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
    private static class H2ShowCreateDataSource implements DataSource
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
         * SHOW CREATE TABLE `t` → SCRIPT NODATA TABLE "T"（H2 等价 DDL），并改写为
         * 反引号形态对齐生产 MySQL SHOW CREATE 输出：过滤 H2 SCRIPT 附属语句
         * （SET 连接参数/CREATE USER/ALTER TABLE 补充约束/行数注释——U2 白名单只放行
         * DROP/CREATE/INSERT），CREATE MEMORY TABLE 去 MEMORY 与 "PUBLIC". schema
         * 前缀，双引号统一换反引号。返回单列单行结果集对齐取值方式。
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
                    if (line == null)
                    {
                        continue;
                    }
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("SET ")
                            || trimmed.startsWith("CREATE USER") || trimmed.startsWith("ALTER TABLE")
                            || trimmed.startsWith("--"))
                    {
                        continue;
                    }
                    if (trimmed.startsWith("CREATE MEMORY TABLE ")
                            || trimmed.startsWith("CREATE CACHED TABLE "))
                    {
                        String rewritten = trimmed.replaceFirst("CREATE (MEMORY|CACHED) TABLE ", "CREATE TABLE ");
                        rewritten = rewritten.replace("\"PUBLIC\".", "");
                        ddl.append(rewritten.replace('"', '`')).append('\n');
                    }
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
