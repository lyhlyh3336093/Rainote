package com.ruoyi.system.service.impl;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.agent.annotation.AgentOperation;
import com.ruoyi.system.domain.dto.RestoreResult;
import com.ruoyi.system.service.BackupLockUtil;
import com.ruoyi.system.service.IBackupRestoreService;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysDictTypeService;
import com.ruoyi.system.service.RestoreSchedulerHook;
import com.ruoyi.system.service.impl.RestoreSqlSanitizer.ParsedStatement;
import com.ruoyi.system.service.impl.RestoreSqlSanitizer.Type;

/**
 * 整机迁移备份/还原服务实现（U1 备份侧 + U2 还原侧）。
 * <p>
 * 备份 = 全库表结构+数据（JDBC 逻辑导出，KTD1）+ profile 下 upload/avatar/import
 * 三目录 + manifest 清单，经 {@link ZipOutputStream} 流式写入 backupPath 落盘
 * （KTD4：独立于 profile 的专有目录，分槽位各保留最近 1 份）。download 目录与
 * backupPath 自身不入包（R2/R3）。触发级写锁（KTD3）：backup() 为入口检查薄壳，
 * exportInternal() 不检锁——供 restore 生成还原前快照复用（重入拆分）。
 * <p>
 * 还原（KTD2/KTD6）：校验阶段（不写库）= 解压防护（路径穿越/体量上限）→ manifest →
 * 指纹比对 → SQL 白名单词法级净化（{@link RestoreSqlSanitizer}，解析产物重渲染后执行）；
 * 执行阶段 = 写锁+暂停调度器（经 {@link RestoreSchedulerHook} 接口反转）→ 快照 →
 * 单连接顺序执行 → 两段式文件替换（staging 暂存）；失败回放快照（受限内部路径：
 * 免指纹/免快照生成/免 confirmed/保留白名单/失败不递归）+ staging 回移。
 * <p>
 * 数据导出为逐行 INSERT（每行一条语句，为 U2 白名单解析器提供确定形态），
 * SELECT 采用 LIMIT/OFFSET 分页批查控制堆内存上界。
 *
 * @author ruoyi
 */
@Service
public class BackupRestoreServiceImpl implements IBackupRestoreService
{
    private static final Logger log = LoggerFactory.getLogger(BackupRestoreServiceImpl.class);

    /** 备份包格式版本 */
    public static final int PACKAGE_VERSION = 1;

    /** 数据分页批查行数（LIMIT/OFFSET，控制结果集堆内存上界） */
    private static final int BATCH_SIZE = 5000;

    /** Quartz 表前缀（KTD1：环境初始化差异，不纳入备份） */
    private static final String QRTZ_PREFIX = "QRTZ_";

    /** 常规备份槽位文件名（backupPath 下，保留最近 1 份） */
    public static final String BACKUP_SLOT = "backup.zip";

    /** 还原前快照槽位文件名（U2 restore 复用 exportInternal 时使用，与常规备份分槽互不顶替） */
    public static final String SNAPSHOT_SLOT = "snapshot.zip";

    /** manifest.json 固定文件名 */
    private static final String MANIFEST_FILENAME = "manifest.json";

    /** 管理员用户 id（agent 备份的服务层兜底判定口径，与界面端点 getUserId()==1 一致，KTD7） */
    private static final Long ADMIN_USER_ID = Long.valueOf(1L);

    /** 非管理员拒绝文案（与界面 dataBak/restore 端点一致） */
    private static final String ADMIN_ONLY_MESSAGE = "只有管理员才可执行此操作";

    /** 入包的用户文件目录（profile 下；download 目录不入包，R2/R3） */
    private static final String[] FILE_DIRS = {"upload", "avatar", "import"};

    /** 文件流式拷贝缓冲区大小 */
    private static final int COPY_BUFFER_SIZE = 8192;

    /** 解压临时目录名（backupPath 下，请求结束删除，启动时清理 >24h 残留） */
    private static final String TMP_DIR = "tmp";

    /** 文件替换暂存目录名（backupPath 下，swap 期间存放旧三目录；排除于 >24h 清理） */
    private static final String STAGING_DIR = "staging";

    /** staging 回移失败重试次数（立即重试） */
    private static final int ROLLBACK_ATTEMPTS = 3;

    /** staging 回移重试间隔（毫秒） */
    private static final long ROLLBACK_RETRY_INTERVAL_MS = 100L;

    /** 解压临时目录保留时长上限（小时） */
    private static final long TMP_TTL_HOURS = 24L;

    /** INSERT 批执行批大小（单 Connection 逐条执行的性能折衷） */
    private static final int INSERT_BATCH_SIZE = 1000;

    /**
     * 用户文件三目录不可用标志（KTD6）：运行期 staging 回移重试耗尽后置位并告警，
     * 拒绝后续还原文件替换，直至人工处理（启动兜底回移成功后由运维确认恢复）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean FILE_DIRS_UNAVAILABLE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 还原回放失败终态标志（P1）：replaySnapshot 失败（含回放路径任何失败）置位——
     * backup() 入口拒绝（引导用还原前快照自愈）、restore() 指纹校验前豁免
     * （还原前快照包免指纹通道自愈）；还原成功或回放成功后清除。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean RESTORE_INCONSISTENT =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 不一致状态统一引导文案：用还原前快照执行还原以恢复（不再承诺"重启自愈"） */
    private static final String INCONSISTENT_GUIDE_MESSAGE =
            "系统处于不一致状态，请在界面使用还原前快照（backupPath/snapshot.zip）执行还原以恢复";

    /** 崩溃重启自愈 marker 文件名（backupPath 下：restore 执行阶段写入，还原成功或回放成功后删除） */
    private static final String RESTORE_MARKER_FILENAME = "restore-in-progress.marker";

    /** 十六进制大写查表：hexLiteral 与指纹/哈希 toHex 共用（P15 去 String.format） */
    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    /** P3：LocalDateTime 显式格式化 → yyyy-MM-dd HH:mm:ss（空格分隔，消除 toString 的 'T' 依赖） */
    private static final DateTimeFormatter DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** P3：LocalDate → yyyy-MM-dd */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** P3：LocalTime → HH:mm:ss */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 还原流程的调度器回调（KTD6 接口反转）：接口在 ruoyi-system、实现在 ruoyi-quartz；
     * quartz 模块未装配（单测直接实例化）时为 null，还原照常运行、仅跳过调度器联动
     */
    @Autowired(required = false)
    private RestoreSchedulerHook schedulerHook;

    /** 还原成功后重建字典缓存（同模块直注入；测试环境可缺省跳过） */
    @Autowired(required = false)
    private ISysDictTypeService dictTypeService;

    /** 还原成功后重建参数缓存（同模块直注入；测试环境可缺省跳过） */
    @Autowired(required = false)
    private ISysConfigService configService;

    /** 解压条目数上限（解压炸弹防护，测试可调小验证超限拒绝） */
    private int maxExtractEntries = 50000;

    /** 总解压大小上限（解压炸弹防护，测试可调小验证超限拒绝） */
    private long maxExtractBytes = 4L * 1024 * 1024 * 1024;

    /**
     * 数据源（druid 动态数据源 master）：元数据与导出直取原生连接，不经过 MyBatis
     */
    @Autowired
    private DataSource dataSource;

    /**
     * 触发整机备份（KTD3 入口薄壳）：加写锁 → exportInternal 导出落盘备份槽位 → finally 解锁。
     *
     * @return 备份包文件（backupPath/backup.zip）
     */
    @Override
    public File backup()
    {
        // P1 回放失败终态：不一致标志置位即拒绝备份，引导用还原前快照执行还原自愈
        if (RESTORE_INCONSISTENT.get())
        {
            throw new ServiceException(INCONSISTENT_GUIDE_MESSAGE);
        }
        if (!BackupLockUtil.tryAcquireBackupLock())
        {
            throw new ServiceException(BackupLockUtil.LOCKED_MESSAGE);
        }
        try
        {
            return exportInternal(BACKUP_SLOT);
        }
        finally
        {
            BackupLockUtil.releaseLock();
        }
    }

    /**
     * Agent 操作入口（U4/KTD7）：触发整机备份并返回结构化成功消息。
     * <p>
     * 鉴权双层：{@code @AgentOperation} 声明<strong>非空 permissionKey</strong>
     * （note:note:delete，与界面 dataBak/restore 端点的 @PreAuthorize 同键——agent
     * 执行器 Layer 3 权限闸门按此校验；空键时闸门放行，任一登录用户即可触发全库导出），
     * 本方法入口再做 userId==1 管理员兜底判定（服务层第二层）。与界面同一后端能力
     * （直调 backup()），受同一触发级写锁约束；agent 无文件下载通道，结果只返回
     * 统计摘要与界面下载引导。agent 不触发还原（预览+二次确认无法映射 agent 交互，
     * 还原仅经界面 POST /system/note/restore）。
     *
     * @param userId 框架注入的当前用户 id（不暴露给 LLM）
     * @return 备份完成消息（表数/记录数/文件数/包大小/文件名 + 界面下载引导：
     *         「数据备份」按钮将基于当前数据重新生成并下载，非直接下载本次生成的文件）
     */
    @AgentOperation(name = "note.backup", permissionKey = "note:note:delete",
            description = "触发整机数据备份：将全库表结构与数据及用户文件目录（upload/avatar/import）"
                    + "打包为迁移备份包，仅管理员可执行。返回备份统计（表数/记录数/文件数/包大小/文件名）；"
                    + "备份文件不随消息返回，界面「数据备份」按钮将基于当前数据生成最新备份并下载"
                    + "（非直接下载本次生成的文件）；生成后覆盖槽位内旧备份，仅保留最近 1 份。"
                    + "数据还原（上传备份包恢复）不支持经 agent 触发，请在界面操作。")
    public String backupForAgent(Long userId)
    {
        // KTD7 服务层管理员兜底：permissionKey 之外的第二层判定，非 userId==1（含 null）拒绝
        if (!ADMIN_USER_ID.equals(userId))
        {
            throw new ServiceException(ADMIN_ONLY_MESSAGE);
        }
        File backup = backup();
        return buildBackupMessage(backup);
    }

    /**
     * 组装 agent 备份成功消息：从备份包 manifest 读统计（表数/记录数/文件数），
     * 包大小取落盘文件长度；manifest 读取失败不影响备份完成事实，退化为
     * 仅包大小/文件名+引导的降级消息。
     */
    private String buildBackupMessage(File backup)
    {
        StringBuilder message = new StringBuilder("备份完成：");
        try (InputStream in = new BufferedInputStream(
                Files.newInputStream(backup.toPath()), COPY_BUFFER_SIZE))
        {
            JSONObject manifest = findManifestInZip(in);
            if (manifest != null)
            {
                long records = 0L;
                JSONArray tables = manifest.getJSONArray("tables");
                if (tables != null)
                {
                    for (int i = 0; i < tables.size(); i++)
                    {
                        records += tables.getJSONObject(i).getLongValue("rows");
                    }
                    message.append("共 ").append(tables.size()).append(" 张表、")
                            .append(records).append(" 条记录、");
                }
                JSONObject fileStats = manifest.getJSONObject("fileStats");
                if (fileStats != null)
                {
                    message.append(fileStats.getLongValue("totalFiles")).append(" 个文件、");
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("[BACKUP] agent 消息读取 manifest 统计失败 {}", backup.getName(), e);
        }
        message.append(String.format("包大小 %.2f MB（%s）。", backup.length() / 1024.0 / 1024.0,
                        backup.getName()))
                .append("备份文件不随消息直接下载：界面「数据备份」按钮将基于当前数据生成最新备份并下载")
                .append("（非直接下载本次生成的文件），生成后覆盖槽位内旧备份，仅保留最近 1 份。");
        return message.toString();
    }

    /**
     * 流式扫描 zip 定位 manifest.json 条目并解析（manifest 由本服务导出时最后写入，
     * 常规备份包必然存在；找不到返回 null 走降级消息）。
     */
    private JSONObject findManifestInZip(InputStream in) throws IOException
    {
        try (ZipInputStream zis = new ZipInputStream(in))
        {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null)
            {
                if (MANIFEST_FILENAME.equals(entry.getName()))
                {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int n;
                    while ((n = zis.read(buffer)) >= 0)
                    {
                        bos.write(buffer, 0, n);
                    }
                    return JSONObject.parseObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                }
            }
        }
        return null;
    }

    /**
     * 内部导出（KTD3 重入拆分）：不检查写锁，供 restore 生成还原前快照复用
     * （restore 已持有写锁，快照复用不得撞自身置位的锁）。
     * <p>
     * zip 布局：manifest.json / schema/&lt;table&gt;.sql / data/&lt;table&gt;.sql / files/{upload,avatar,import}/**。
     * 流式落盘：ZipOutputStream 直接包裹 FileOutputStream 逐条目写，全程不整包驻留堆内。
     *
     * @param targetSlot 目标槽位文件名（backup.zip=常规备份 / snapshot.zip=还原前快照，分槽各保留最近 1 份）
     * @return 备份包文件
     */
    public File exportInternal(String targetSlot)
    {
        Path backupDir = Paths.get(RuoYiConfig.getBackupPath());
        Path tmpFile = backupDir.resolve(targetSlot + ".tmp");
        Path finalFile = backupDir.resolve(targetSlot);
        boolean success = false;
        int tableCount = 0;
        long fileCount = 0;
        try
        {
            Files.createDirectories(backupDir);
            try (Connection conn = dataSource.getConnection();
                 ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmpFile)))
            {
                // P2 一致性快照（mysqldump --single-transaction 同款）：REPEATABLE READ +
                // START TRANSACTION WITH CONSISTENT SNAPSHOT，导出期间单一读视图；
                // 完成后 rollback 并恢复 autocommit
                boolean previousAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (Statement txStmt = conn.createStatement())
                {
                    txStmt.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                    txStmt.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT");
                }
                try
                {
                    String catalog = conn.getCatalog();
                    // KTD1：catalog 限定连接库 + 类型 TABLE + QRTZ_ 前缀排除
                    List<String> tables = listTables(conn, catalog);

                    // 逐表导出结构+数据，同时收集指纹素材与行数
                    List<String> fingerprintLines = new ArrayList<>();
                    List<JSONObject> tableStats = new ArrayList<>();
                    for (String table : tables)
                    {
                        List<String[]> columns = tableColumns(conn, catalog, table);
                        for (String[] col : columns)
                        {
                            fingerprintLines.add(fingerprintLine(table, col));
                        }
                        exportSchema(conn, zos, table);
                        long rows = exportData(conn, zos, table, columns);
                        JSONObject stat = new JSONObject();
                        stat.put("name", table);
                        stat.put("rows", rows);
                        tableStats.add(stat);
                    }

                    // 用户文件三目录流式入包（download 不入包）
                    long[] fileStats = copyFileDirs(zos);

                    // manifest 最后写入（借鉴 NoteDwtableExportPackager 的 entry 组织）
                    writeManifest(zos, computeFingerprint(fingerprintLines), tableStats, fileStats);
                    tableCount = tableStats.size();
                    fileCount = fileStats[0];
                }
                finally
                {
                    try
                    {
                        conn.rollback();
                    }
                    catch (SQLException e)
                    {
                        log.warn("[BACKUP] 一致性快照事务回滚失败（best-effort）", e);
                    }
                    try
                    {
                        conn.setAutoCommit(previousAutoCommit);
                    }
                    catch (SQLException e)
                    {
                        log.warn("[BACKUP] 恢复连接 autocommit 失败（best-effort）", e);
                    }
                }
            }
            // try-with-resources 已关闭 zip 流，此时原子替换槽位文件：
            // 分槽位保留最近 1 份，且不会以半损坏包顶替旧恢复点
            Files.move(tmpFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
            success = true;
            log.info("[BACKUP] 备份包生成完成 slot={}, tables={}, files={}", targetSlot, tableCount, fileCount);
            return finalFile.toFile();
        }
        catch (SQLException | IOException e)
        {
            log.error("[BACKUP] 备份包生成失败 slot={}", targetSlot, e);
            throw new ServiceException("备份失败：无法生成备份包");
        }
        finally
        {
            if (!success)
            {
                try
                {
                    Files.deleteIfExists(tmpFile);
                }
                catch (IOException e)
                {
                    log.warn("[BACKUP] 临时文件清理失败 {}", tmpFile, e);
                }
            }
        }
    }

    /**
     * 取表清单：DatabaseMetaData.getTables 按 catalog 限定连接库、类型 TABLE 过滤，
     * 追加 QRTZ_ 前缀排除（KTD1），结果排序保证导出顺序稳定。
     */
    private List<String> listTables(Connection conn, String catalog) throws SQLException
    {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getTables(catalog, null, "%", new String[] {"TABLE"}))
        {
            while (rs.next())
            {
                String name = rs.getString("TABLE_NAME");
                if (name != null && !name.toUpperCase(Locale.ROOT).startsWith(QRTZ_PREFIX))
                {
                    tables.add(name);
                }
            }
        }
        Collections.sort(tables);
        return tables;
    }

    /**
     * 取表列清单（保持 ordinal 顺序）：每项为 [列名, 列类型名, 列大小, 小数位]
     * （P2 指纹精度维度：COLUMN_SIZE/DECIMAL_DIGITS 入指纹素材，null 由素材行归一），
     * 供 INSERT 列段与指纹使用。
     */
    private List<String[]> tableColumns(Connection conn, String catalog, String table) throws SQLException
    {
        List<String[]> columns = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getColumns(catalog, null, table, "%"))
        {
            while (rs.next())
            {
                columns.add(new String[] {rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"),
                        rs.getString("COLUMN_SIZE"), rs.getString("DECIMAL_DIGITS")});
            }
        }
        return columns;
    }

    /**
     * 指纹素材行（P2 精度维度扩展）：table\tcol\ttype\tsize\tdigits（size/digits null 归一为
     * 空串）——导出侧与 computeCurrentFingerprint 共用同一方法，两侧格式自动同步。
     */
    private static String fingerprintLine(String table, String[] column)
    {
        String size = column[2] == null ? "" : column[2];
        String digits = column[3] == null ? "" : column[3];
        return table + "\t" + column[0] + "\t" + column[1] + "\t" + size + "\t" + digits;
    }

    /**
     * 导出单表结构：SHOW CREATE TABLE 原文 + 前置 DROP TABLE IF EXISTS，写 schema/&lt;table&gt;.sql。
     */
    private void exportSchema(Connection conn, ZipOutputStream zos, String table) throws SQLException, IOException
    {
        String ddl;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE `" + table + "`"))
        {
            if (!rs.next())
            {
                throw new SQLException("SHOW CREATE TABLE 无结果: " + table);
            }
            // MySQL 返回 (Table, Create Table) 两列，H2 返回单列——取最后一列兼容两端
            ddl = rs.getString(rs.getMetaData().getColumnCount());
        }
        StringBuilder sql = new StringBuilder();
        sql.append("DROP TABLE IF EXISTS `").append(table).append("`;\n");
        sql.append(ddl).append("\n;\n");
        writeZipEntry(zos, "schema/" + table + ".sql", sql.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 导出单表数据：SELECT * ... LIMIT ? OFFSET ? 分页批查（5000 行/批），
     * 逐行生成 INSERT INTO `表`(`列`,...) VALUES(...) 语句写 data/&lt;table&gt;.sql。
     *
     * @return 导出总行数（供 manifest tables[].rows）
     */
    private long exportData(Connection conn, ZipOutputStream zos, String table, List<String[]> columns)
            throws SQLException, IOException
    {
        StringBuilder columnList = new StringBuilder();
        for (int i = 0; i < columns.size(); i++)
        {
            if (i > 0)
            {
                columnList.append(",");
            }
            columnList.append("`").append(columns.get(i)[0]).append("`");
        }
        zos.putNextEntry(new ZipEntry("data/" + table + ".sql"));
        // Writer 不 close（close 会级联关闭 zos），flush 后由 closeEntry 收尾
        Writer writer = new OutputStreamWriter(zos, StandardCharsets.UTF_8);
        String insertPrefix = "INSERT INTO `" + table + "`(" + columnList + ") VALUES(";
        long rows = 0;
        String query = "SELECT * FROM `" + table + "` LIMIT ? OFFSET ?";
        int offset = 0;
        while (true)
        {
            int fetched = 0;
            try (PreparedStatement ps = conn.prepareStatement(query))
            {
                ps.setInt(1, BATCH_SIZE);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery())
                {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();
                    while (rs.next())
                    {
                        writer.write(insertPrefix);
                        for (int i = 1; i <= columnCount; i++)
                        {
                            if (i > 1)
                            {
                                writer.write(",");
                            }
                            writer.write(formatValue(rs, i, meta.getColumnType(i)));
                        }
                        writer.write(");\n");
                        rows++;
                        fetched++;
                    }
                }
            }
            if (fetched < BATCH_SIZE)
            {
                break;
            }
            offset += BATCH_SIZE;
        }
        writer.flush();
        zos.closeEntry();
        return rows;
    }

    /**
     * 按 JDBC 类型把当前行当前列值格式化为 SQL 字面量：
     * NULL；二进制列 hex 0x...；数值原样；时间按标准格式加引号；字符串转义单引号/反斜杠。
     */
    private String formatValue(ResultSet rs, int columnIndex, int sqlType) throws SQLException
    {
        Object value = rs.getObject(columnIndex);
        if (value == null)
        {
            return "NULL";
        }
        if (value instanceof byte[])
        {
            return hexLiteral((byte[]) value);
        }
        if (value instanceof Boolean)
        {
            return ((Boolean) value) ? "TRUE" : "FALSE";
        }
        if (value instanceof Float || value instanceof Double)
        {
            // P1 自洽性：浮点走 BigDecimal.toPlainString 消除科学计数法（如 5.0E-4），
            // 保证导出物必过白名单 NUMERIC_WORD 且重渲染可执行（Double.toString 会产出 5.0E-4）
            return new BigDecimal(((Number) value).doubleValue()).toPlainString();
        }
        if (value instanceof Number)
        {
            return value.toString();
        }
        if (value instanceof Timestamp)
        {
            return "'" + value.toString() + "'";
        }
        if (value instanceof java.sql.Time)
        {
            return "'" + value.toString() + "'";
        }
        if (value instanceof java.sql.Date)
        {
            return "'" + value.toString() + "'";
        }
        if (value instanceof LocalDateTime)
        {
            // P3：java.time 显式格式化（空格分隔，消除 toString 的 'T' 依赖）
            return "'" + DATETIME_FORMAT.format((LocalDateTime) value) + "'";
        }
        if (value instanceof LocalDate)
        {
            return "'" + DATE_FORMAT.format((LocalDate) value) + "'";
        }
        if (value instanceof LocalTime)
        {
            return "'" + TIME_FORMAT.format((LocalTime) value) + "'";
        }
        if (value instanceof java.util.Date)
        {
            // 驱动返回裸 java.util.Date 的兜底：按 Timestamp 语义格式化
            return "'" + new Timestamp(((java.util.Date) value).getTime()).toString() + "'";
        }
        // 字符串与其余类型（含 JSON 列）：字符串转义（P2 转义同源——与重渲染共用同一实现）
        return RestoreSqlSanitizer.renderMySqlString(value.toString());
    }

    /**
     * 二进制列编码为 MySQL hex 字面量（0x...）。P1：空字节数组返回 ''——MySQL 二进制
     * 空串等价（裸 0x 为非法字面量）；P15：HEX 查表实现（去 String.format）。
     */
    private static String hexLiteral(byte[] bytes)
    {
        if (bytes.length == 0)
        {
            return "''";
        }
        return "0x" + toHex(bytes);
    }

    /** 字节数组 → 十六进制大写串（P15 查表；hexLiteral 与指纹/哈希共用） */
    private static String toHex(byte[] bytes)
    {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
        {
            int v = b & 0xFF;
            hex.append(HEX_CHARS[v >>> 4]).append(HEX_CHARS[v & 0x0F]);
        }
        return hex.toString();
    }

    /**
     * 用户文件三目录（upload/avatar/import）流式拷入 files/ 前缀；
     * download 目录与 backupPath 不在拷贝范围（R2/R3 防递归）。
     *
     * @return [文件数, 总字节数]（供 manifest fileStats）
     */
    private long[] copyFileDirs(ZipOutputStream zos) throws IOException
    {
        Path profile = Paths.get(RuoYiConfig.getProfile());
        long totalFiles = 0;
        long totalBytes = 0;
        for (String dir : FILE_DIRS)
        {
            Path srcDir = profile.resolve(dir);
            if (!Files.isDirectory(srcDir))
            {
                continue;
            }
            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(srcDir))
            {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
            Collections.sort(files);
            for (Path file : files)
            {
                String relative = srcDir.relativize(file).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry("files/" + dir + "/" + relative));
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file), COPY_BUFFER_SIZE))
                {
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int n;
                    while ((n = in.read(buffer)) >= 0)
                    {
                        zos.write(buffer, 0, n);
                        totalBytes += n;
                    }
                }
                zos.closeEntry();
                totalFiles++;
            }
        }
        return new long[] {totalFiles, totalBytes};
    }

    /**
     * 表结构指纹（KTD2）：全部（表名,列名,列类型,精度）素材行排序后逐行 SHA-256——
     * 排序保证同一库指纹稳定（与列定义顺序无关），增删列/改类型/改列精度即变化。
     */
    private String computeFingerprint(List<String> fingerprintLines)
    {
        Collections.sort(fingerprintLines);
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : fingerprintLines)
            {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            // P15：与 hexLiteral 共用 toHex 查表实现，素材行两侧自动同步
            return toHex(digest.digest());
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new ServiceException("备份失败：SHA-256 不可用");
        }
    }

    /**
     * 写 manifest.json：packageVersion / schemaFingerprint / createdAt / tables[{name,rows}] / fileStats。
     */
    private void writeManifest(ZipOutputStream zos, String fingerprint, List<JSONObject> tableStats, long[] fileStats)
            throws IOException
    {
        // fastjson2 的 JSONObject 基于 LinkedHashMap，天然保持字段插入顺序
        JSONObject manifest = new JSONObject();
        manifest.put("packageVersion", PACKAGE_VERSION);
        manifest.put("schemaFingerprint", fingerprint);
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("tables", tableStats);
        JSONObject stats = new JSONObject();
        stats.put("totalFiles", fileStats[0]);
        stats.put("totalBytes", fileStats[1]);
        manifest.put("fileStats", stats);
        writeZipEntry(zos, MANIFEST_FILENAME, manifest.toJSONString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 写单个 zip 文本 entry
     */
    private void writeZipEntry(ZipOutputStream zos, String entryName, byte[] content) throws IOException
    {
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(content);
        zos.closeEntry();
    }

    // ==================== U2：还原侧（校验 / 执行 / 快照回放 / 文件两段式） ====================

    /**
     * 启动清理（KTD4/KTD6）：清理 backupPath/tmp 下超过 24h 的解压临时目录残留
     * （staging 排除于时间清理），并检测 staging 残留做回移兜底（上次进程异常退出
     * 留下的未完成 swap）。清理失败只记告警，不影响启动。
     */
    @PostConstruct
    public void startupCleanup()
    {
        try
        {
            Path tmp = Paths.get(RuoYiConfig.getBackupPath()).resolve(TMP_DIR);
            if (Files.isDirectory(tmp))
            {
                Instant cutoff = Instant.now().minusSeconds(TMP_TTL_HOURS * 3600);
                List<Path> children = new ArrayList<>();
                try (Stream<Path> stream = Files.list(tmp))
                {
                    stream.forEach(children::add);
                }
                for (Path child : children)
                {
                    if (Files.getLastModifiedTime(child).toInstant().isBefore(cutoff))
                    {
                        deleteTree(child);
                        log.info("[RESTORE] 清理超期临时目录 {}", child);
                    }
                }
            }
            rollbackStaging();
            // P1 崩溃重启自愈：检测未完成还原的 marker 残留（上次进程在还原执行阶段退出）
            recoverInterruptedRestore();
        }
        catch (Throwable e)
        {
            log.warn("[RESTORE] 启动清理失败（不影响启动）", e);
        }
    }

    /**
     * P1 崩溃重启自愈：marker 存在且还原前快照在位 → 自动回放快照把库拉回还原前状态
     * （免指纹受限路径）；成功删 marker 并 log.warn 告知"已自动回退"，失败置不一致
     * 标志 + log.error（不阻止启动）。快照缺失时仅记 error（无法自愈，请人工处理）。
     */
    private void recoverInterruptedRestore()
    {
        Path marker = Paths.get(RuoYiConfig.getBackupPath()).resolve(RESTORE_MARKER_FILENAME);
        if (!Files.isRegularFile(marker))
        {
            return;
        }
        Path snapshot = Paths.get(RuoYiConfig.getBackupPath()).resolve(SNAPSHOT_SLOT);
        if (!Files.isRegularFile(snapshot))
        {
            log.error("[RESTORE] 检测到未完成还原的 marker 但还原前快照缺失，无法自动回退，请人工处理");
            return;
        }
        try
        {
            replaySnapshot();
            Files.deleteIfExists(marker);
            log.warn("[RESTORE] 检测到未完成还原已自动回退");
        }
        catch (Throwable t)
        {
            // replaySnapshot 内部已置不一致标志，此处兜底再保证置位（不阻止启动）
            RESTORE_INCONSISTENT.set(true);
            log.error("[RESTORE] 启动自动回退失败，系统处于不一致状态（不阻止启动）", t);
        }
    }

    /**
     * 还原校验与预览（U2，不写库）：解压防护 → manifest → 指纹比对 → 白名单净化 →
     * 统计预览。请求结束删除本次解压临时目录。
     */
    @Override
    public RestoreResult previewRestore(MultipartFile file)
    {
        ValidatedPackage pkg = null;
        try
        {
            pkg = validatePackage(file, true);
            return buildResult(pkg, false);
        }
        finally
        {
            if (pkg != null)
            {
                deleteTree(pkg.extractDir);
            }
        }
    }

    /**
     * 还原执行（U2，F2 时序）。confirmed=false 直接走预览（P2 预览不持锁——校验不写库，
     * 无需与备份/还原互斥）；confirmed=true 对本次上传文件重跑全部校验（不信任跨请求
     * 预览）后按 快照→执行→两段式文件替换 推进，任一步失败回放快照+staging 回移
     * （KTD6 两段失败区分），成功后重建缓存与调度器并做会话判定。try-finally 保证
     * 调度器恢复与锁释放（P2 顺序：resume→releaseLock→临时目录清理，防清理异常卡死
     * 锁/调度器；locked 标志条件释放，防误清并发持有者）。
     */
    @Override
    public RestoreResult restore(MultipartFile file, boolean confirmed)
    {
        // P2 预览不持锁：未确认直接走校验预览（无锁提前返回）
        if (!confirmed)
        {
            return previewRestore(file);
        }
        boolean locked = BackupLockUtil.tryAcquireBackupLock();
        if (!locked)
        {
            throw new ServiceException(BackupLockUtil.LOCKED_MESSAGE);
        }
        ValidatedPackage pkg = null;
        boolean schedulerPaused = false;
        try
        {
            // confirmed 请求重跑全部校验（R5：不跨请求信任预览结果）
            pkg = validatePackage(file, true);
            // KTD6：先暂停调度器再动库（防调度任务在库重建窗口写半还原数据）
            if (schedulerHook != null)
            {
                schedulerHook.pause();
                schedulerPaused = true;
            }
            // 还原前快照（复用 exportInternal 不撞自身写锁；快照生成阶段失败→直接失败
            // 返回，库未动、无需回放）
            exportInternal(SNAPSHOT_SLOT);
            // P1 崩溃自愈 marker：执行阶段开始（快照已生成、SQL 执行前）写入，
            // 还原成功或回放成功后删除
            writeRestoreMarker();
            try
            {
                executePackageSqls(pkg);
                swapFileDirs(pkg);
            }
            catch (Throwable t)
            {
                recoverFromFailure(t);
                throw new ServiceException("还原失败：已回滚至还原前状态，原因：" + t.getMessage());
            }
            try
            {
                rebuildCaches();
                if (schedulerHook != null)
                {
                    // 恶意包植入无效 cron 会让重载抛异常——走失败回放分支
                    schedulerHook.reload();
                }
            }
            catch (Throwable t)
            {
                recoverFromFailure(t);
                // P1 reload 失败重对齐：回放成功后 best-effort 重同步缓存与调度器
                // （回放后 sys_job 为还原前数据，重载必成；仍失败仅记 error 不再升级）
                try
                {
                    rebuildCaches();
                    if (schedulerHook != null)
                    {
                        schedulerHook.reload();
                    }
                }
                catch (Throwable resyncEx)
                {
                    log.error("[RESTORE] 回放后缓存/调度器重对齐失败", resyncEx);
                }
                throw new ServiceException("还原失败：已回滚至还原前状态，原因：" + t.getMessage());
            }
            cleanupStagingOnSuccess();
            // P1：还原成功——清除不一致标志（自愈完成，若有）与崩溃自愈 marker
            RESTORE_INCONSISTENT.set(false);
            deleteRestoreMarker();
            log.info("[RESTORE] 还原完成 tables={} records={} files={}", pkg.tables.size(),
                    pkg.recordCount, pkg.fileCount);
            return buildResult(pkg, true);
        }
        finally
        {
            // P2 顺序：先恢复调度器与写锁，最后清理临时目录（best-effort 永不外抛）
            if (schedulerPaused && schedulerHook != null)
            {
                try
                {
                    schedulerHook.resume();
                }
                catch (Throwable e)
                {
                    log.error("[RESTORE] 调度器恢复失败", e);
                }
            }
            if (locked)
            {
                BackupLockUtil.releaseLock();
            }
            if (pkg != null)
            {
                deleteTree(pkg.extractDir);
            }
        }
    }

    /**
     * 执行阶段失败恢复（KTD6）：回放还原前快照（受限内部路径）+ staging 回移。
     * 回放自身失败不递归——replaySnapshot 已置位不一致标志，立即终止并抛统一引导
     * 文案（用还原前快照执行还原以恢复，不再承诺"重启自愈"，不静默吞掉）；
     * 文件回移尽力执行，失败置三目录不可用标志并告警。
     */
    private void recoverFromFailure(Throwable cause)
    {
        log.error("[RESTORE] 还原执行失败，开始回放还原前快照", cause);
        ServiceException inconsistent = null;
        try
        {
            replaySnapshot();
        }
        catch (Throwable replayEx)
        {
            log.error("[RESTORE] 快照回放失败，系统处于不一致状态", replayEx);
            inconsistent = new ServiceException("还原失败且回滚未完成：" + INCONSISTENT_GUIDE_MESSAGE);
        }
        rollbackStaging();
        if (inconsistent != null)
        {
            throw inconsistent;
        }
    }

    /**
     * 回放还原前快照（KTD6 受限内部路径，非"再执行一次 restore"）：
     * 免指纹校验（现库已知半还原、指纹必不匹配）、免快照生成（不得用半损坏库顶替
     * 唯一恢复点）、免 confirmed；保留解压防护与 SQL 白名单（快照自产可信、过白名单
     * 零成本）；失败不递归——向上抛出由调用方终止。包内可见供测试与应急调用。
     * <p>
     * P1 终态语义：回放成功→清除不一致标志并删除崩溃自愈 marker（库回到还原前一致
     * 状态）；回放路径任何失败（含清单校验/读取/执行）→置位不一致标志后原样重抛
     * （backup 拒绝、restore 免指纹通道自愈）。
     */
    void replaySnapshot()
    {
        try
        {
            replaySnapshotInternal();
        }
        catch (Throwable t)
        {
            // 回放路径任何失败置位：系统处于不一致状态（backup 拒绝、快照包免指纹自愈）
            RESTORE_INCONSISTENT.set(true);
            log.error("[RESTORE] 快照回放失败，系统处于不一致状态，请用还原前快照执行还原以恢复", t);
            throw t;
        }
    }

    /** 回放内部实现（P1：异常统一由 {@link #replaySnapshot()} 包装置位后重抛） */
    private void replaySnapshotInternal()
    {
        Path snapshot = Paths.get(RuoYiConfig.getBackupPath()).resolve(SNAPSHOT_SLOT);
        if (!Files.isRegularFile(snapshot))
        {
            throw new ServiceException("还原前快照缺失，" + INCONSISTENT_GUIDE_MESSAGE);
        }
        Path extractDir = null;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(snapshot), COPY_BUFFER_SIZE))
        {
            extractDir = extractZip(in);
            JSONObject manifest = readManifest(extractDir);
            ValidatedPackage pkg = new ValidatedPackage();
            pkg.extractDir = extractDir;
            pkg.tables = readTableList(manifest);
            executePackageSqls(pkg);
        }
        catch (IOException e)
        {
            throw new ServiceException("还原前快照包读取失败，" + INCONSISTENT_GUIDE_MESSAGE);
        }
        finally
        {
            if (extractDir != null)
            {
                deleteTree(extractDir);
            }
        }
        // P1：回放成功——库回到还原前一致状态，清除不一致标志与崩溃自愈 marker
        RESTORE_INCONSISTENT.set(false);
        deleteRestoreMarker();
        log.info("[RESTORE] 还原前快照回放完成");
    }

    /**
     * 校验包并构建（U2 校验阶段，不写库）：解压（含防护）→ manifest（版本/表清单）→
     * 指纹比对（可选）→ 白名单净化全 SQL 文件（统计+sys_user 收集）。
     * 校验失败删除本次解压目录；成功时解压目录保留给执行阶段，由调用方负责清理。
     *
     * @param checkFingerprint true=比对包内指纹与现库（还原/预览）；false=跳过（回放受限路径不经过本方法）
     */
    private ValidatedPackage validatePackage(MultipartFile file, boolean checkFingerprint)
    {
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("上传的备份包为空");
        }
        Path extractDir;
        try (InputStream in = file.getInputStream())
        {
            extractDir = extractZip(in);
        }
        catch (IOException e)
        {
            log.error("[RESTORE] 备份包读取失败", e);
            throw new ServiceException("备份包读取失败");
        }
        boolean success = false;
        try
        {
            ValidatedPackage pkg = new ValidatedPackage();
            pkg.extractDir = extractDir;
            JSONObject manifest = readManifest(extractDir);
            int version = manifest.getIntValue("packageVersion");
            if (version != PACKAGE_VERSION)
            {
                throw new ServiceException("备份包版本不支持: " + version + "，已拒绝还原");
            }
            pkg.tables = readTableList(manifest);
            pkg.fileCount = countExtractedFiles(extractDir.resolve("files"));
            if (checkFingerprint)
            {
                String packageFingerprint = manifest.getString("schemaFingerprint");
                try (Connection conn = dataSource.getConnection())
                {
                    String catalog = conn.getCatalog();
                    // P1 不一致标志置位（回放失败残留）：与空库同款豁免——跳过表集
                    // 核对与指纹比对，放行还原前快照包自愈通道（sys_user 可能已被
                    // 半还原 DROP，现库差异收集一并跳过）
                    boolean fingerprintExempt = RESTORE_INCONSISTENT.get();
                    // P1/AE1：指纹比对前取现库非 QRTZ 表清单
                    List<String> currentTables = listTables(conn, catalog);
                    if (!fingerprintExempt && currentTables.isEmpty())
                    {
                        // 空库无数据可丢，全量重建安全——跳过指纹不匹配拒绝
                        log.info("[RESTORE] 目标库为空，跳过指纹校验");
                        fingerprintExempt = true;
                    }
                    if (!fingerprintExempt)
                    {
                        // P1：manifest 表清单与现库（非 QRTZ）双向集合比对（大小写归一），
                        // 不等即拒绝（防包清单与现库结构漂移）
                        Set<String> manifestTables = new HashSet<>();
                        for (String table : pkg.tables)
                        {
                            manifestTables.add(table.toLowerCase(Locale.ROOT));
                        }
                        Set<String> currentTableSet = new HashSet<>();
                        for (String table : currentTables)
                        {
                            currentTableSet.add(table.toLowerCase(Locale.ROOT));
                        }
                        if (!manifestTables.equals(currentTableSet))
                        {
                            throw new ServiceException("备份包表清单与现库不一致，已拒绝还原");
                        }
                        // KTD2：指纹两侧同算法（表名+列名+列类型+精度排序哈希），不一致拒绝
                        if (!computeCurrentFingerprint(conn).equals(packageFingerprint))
                        {
                            throw new ServiceException("备份包与当前库结构不一致（schema 指纹校验失败），已拒绝还原");
                        }
                        collectUserDifference(conn, pkg);
                    }
                }
                catch (SQLException e)
                {
                    log.error("[RESTORE] 还原校验读取现库失败", e);
                    throw new ServiceException("还原校验失败：无法读取当前库结构");
                }
            }
            // SQL 白名单净化（流式逐语句，同时统计记录数与收集 sys_user 差异素材）
            sanitizePackageSqls(pkg, statement -> {
                if (statement.getType() == Type.INSERT)
                {
                    pkg.recordCount++;
                    collectPackageUserStats(pkg, statement);
                }
            });
            // 差异检测只比对行数与 password 哈希（uid 集合差异同样体现为 map 不等），
            // password 值与哈希不进任何输出
            pkg.credentialChanged = checkFingerprint && !pkg.packageUserHashes.equals(pkg.currentUserHashes);
            success = true;
            return pkg;
        }
        finally
        {
            if (!success)
            {
                deleteTree(extractDir);
            }
        }
    }

    /**
     * ZipInputStream 流式解压到 backupPath/tmp 专用临时目录（R6 防护）：
     * 条目名规范化路径校验（拒绝 ../ 穿越、绝对路径、盘符、反斜杠分隔）+ 条目数
     * 与总解压大小上限；normalize 后 startsWith 双保险。
     */
    private Path extractZip(InputStream in)
    {
        try
        {
            Path tmpRoot = Paths.get(RuoYiConfig.getBackupPath()).resolve(TMP_DIR);
            Files.createDirectories(tmpRoot);
            Path extractDir = Files.createDirectories(tmpRoot.resolve("restore-" + System.nanoTime()));
            boolean success = false;
            try
            {
                try (ZipInputStream zis = new ZipInputStream(in))
                {
                    ZipEntry entry;
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int entries = 0;
                    long totalBytes = 0L;
                    while ((entry = zis.getNextEntry()) != null)
                    {
                        if (++entries > maxExtractEntries)
                        {
                            throw new ServiceException("备份包解压条目数超限，已拒绝还原");
                        }
                        String name = entry.getName();
                        if (name == null || name.isEmpty() || name.startsWith("/")
                                || name.contains("\\") || name.contains(":"))
                        {
                            throw new ServiceException("备份包条目名非法，已拒绝还原");
                        }
                        StringBuilder relative = new StringBuilder();
                        for (String part : name.split("/"))
                        {
                            if (part.isEmpty())
                            {
                                continue;
                            }
                            if (part.equals("..") || part.equals("."))
                            {
                                throw new ServiceException("备份包条目路径穿越，已拒绝还原");
                            }
                            if (relative.length() > 0)
                            {
                                relative.append('/');
                            }
                            relative.append(part);
                        }
                        Path target = extractDir.resolve(relative.toString()).normalize();
                        if (!target.startsWith(extractDir))
                        {
                            throw new ServiceException("备份包条目路径穿越，已拒绝还原");
                        }
                        if (entry.isDirectory())
                        {
                            Files.createDirectories(target);
                            continue;
                        }
                        Files.createDirectories(target.getParent());
                        try (OutputStream out = Files.newOutputStream(target))
                        {
                            int n;
                            while ((n = zis.read(buffer)) >= 0)
                            {
                                out.write(buffer, 0, n);
                                totalBytes += n;
                                // P2 内联熔断：单条目写中途超限立即拒绝（不等条目写完）
                                if (totalBytes > maxExtractBytes)
                                {
                                    throw new ServiceException("备份包解压总量超限，已拒绝还原");
                                }
                            }
                        }
                    }
                }
                success = true;
                return extractDir;
            }
            finally
            {
                if (!success)
                {
                    deleteTree(extractDir);
                }
            }
        }
        catch (IOException e)
        {
            log.error("[RESTORE] 备份包解压失败", e);
            throw new ServiceException("备份包解压失败，已拒绝还原");
        }
    }

    /**
     * 读 manifest.json（缺失/损坏拒绝）
     */
    private JSONObject readManifest(Path extractDir)
    {
        Path manifestFile = extractDir.resolve(MANIFEST_FILENAME);
        if (!Files.isRegularFile(manifestFile))
        {
            throw new ServiceException("备份包缺少 manifest.json，已拒绝还原");
        }
        try
        {
            return JSONObject.parseObject(
                    new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            throw new ServiceException("备份包 manifest.json 损坏，已拒绝还原");
        }
    }

    /**
     * 读 manifest 表清单（还原目标白名单基准；空清单拒绝；P1 fail-closed：含 QRTZ_
     * 保留前缀表直接拒绝还原）
     */
    private List<String> readTableList(JSONObject manifest)
    {
        JSONArray tableArray = manifest.getJSONArray("tables");
        if (tableArray == null || tableArray.isEmpty())
        {
            throw new ServiceException("备份包表清单为空，已拒绝还原");
        }
        List<String> tables = new ArrayList<>(tableArray.size());
        for (int i = 0; i < tableArray.size(); i++)
        {
            String name = tableArray.getJSONObject(i).getString("name");
            if (name == null || name.trim().isEmpty())
            {
                throw new ServiceException("备份包表清单项缺失表名，已拒绝还原");
            }
            if (name.toUpperCase(Locale.ROOT).startsWith(QRTZ_PREFIX))
            {
                throw new ServiceException("备份包表清单含保留前缀表，已拒绝还原");
            }
            tables.add(name);
        }
        return tables;
    }

    /** 统计解压目录 files/ 前缀文件数（预览 fileCount） */
    private long countExtractedFiles(Path filesRoot)
    {
        if (!Files.isDirectory(filesRoot))
        {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(filesRoot))
        {
            return walk.filter(Files::isRegularFile).count();
        }
        catch (IOException e)
        {
            log.error("[RESTORE] 备份包文件统计失败", e);
            throw new ServiceException("备份包文件统计失败");
        }
    }

    /**
     * 白名单净化包内全部 SQL 文件（流式逐语句回调）
     */
    private void sanitizePackageSqls(ValidatedPackage pkg, Consumer<ParsedStatement> statistic)
    {
        Set<String> allowed = pkg.allowedTableSet();
        for (String table : pkg.tables)
        {
            consumeSqlFile(pkg.extractDir, "schema/" + table + ".sql", allowed, statistic);
            consumeSqlFile(pkg.extractDir, "data/" + table + ".sql", allowed, statistic);
        }
    }

    /**
     * 单个 SQL 文件净化消费：缺文件拒绝；Reader 流式喂给状态机
     */
    private void consumeSqlFile(Path extractDir, String entry, Set<String> allowedTables,
            Consumer<ParsedStatement> consumer)
    {
        Path sqlFile = resolveExtractFile(extractDir, entry);
        if (!Files.isRegularFile(sqlFile))
        {
            throw new ServiceException("备份包缺少文件: " + entry + "，已拒绝还原");
        }
        try (Reader reader = Files.newBufferedReader(sqlFile, StandardCharsets.UTF_8))
        {
            RestoreSqlSanitizer.parseAndConsume(entry, reader, allowedTables, consumer);
        }
        catch (IOException e)
        {
            throw new ServiceException("备份包文件读取失败: " + entry);
        }
    }

    /**
     * 解压目录内 entry 解析（manifest 表名拼接路径，防伪造穿越）
     */
    private Path resolveExtractFile(Path extractDir, String entry)
    {
        if (entry.contains("\\") || entry.startsWith("/"))
        {
            throw new ServiceException("备份包文件路径非法，已拒绝还原");
        }
        Path resolved = extractDir.resolve(entry).normalize();
        if (!resolved.startsWith(extractDir))
        {
            throw new ServiceException("备份包文件路径非法，已拒绝还原");
        }
        return resolved;
    }

    /**
     * 单 Connection 顺序执行净化后 SQL（KTD6）：会话级 FOREIGN_KEY_CHECKS=0 →
     * 逐表 DROP→CREATE→INSERT（重渲染串，一致性由快照回放兜底，不依赖 DB 事务）。
     * P2 autocommit 优化：整包执行关 autocommit，每表（schema+data 执行完）commit 一次，
     * finally 兜底 rollback 未提交部分（已提交表由快照回放兜底）。INSERT 每 1000 条一批发送。
     */
    private void executePackageSqls(ValidatedPackage pkg)
    {
        try (Connection conn = dataSource.getConnection())
        {
            conn.setAutoCommit(false);
            try
            {
                Set<String> allowed = pkg.allowedTableSet();
                try (Statement stmt = conn.createStatement())
                {
                    stmt.execute("SET FOREIGN_KEY_CHECKS=0");
                    for (String table : pkg.tables)
                    {
                        executeSqlFile(pkg.extractDir, "schema/" + table + ".sql", allowed, stmt, false);
                        executeSqlFile(pkg.extractDir, "data/" + table + ".sql", allowed, stmt, true);
                        conn.commit();
                    }
                }
            }
            finally
            {
                try
                {
                    conn.rollback();
                }
                catch (SQLException e)
                {
                    log.warn("[RESTORE] 还原执行事务兜底回滚失败（best-effort）", e);
                }
            }
        }
        catch (SQLException e)
        {
            log.error("[RESTORE] 还原执行失败", e);
            throw new ServiceException("还原执行失败：" + e.getMessage());
        }
    }

    /**
     * 执行单个净化 SQL 文件：重渲染串逐语句发送（DDL 单发；INSERT 批发）
     */
    private void executeSqlFile(Path extractDir, String entry, Set<String> allowedTables,
            Statement stmt, boolean batchInsert) throws SQLException
    {
        Path sqlFile = resolveExtractFile(extractDir, entry);
        List<String> batch = batchInsert ? new ArrayList<>() : null;
        try (Reader reader = Files.newBufferedReader(sqlFile, StandardCharsets.UTF_8))
        {
            RestoreSqlSanitizer.parseAndConsume(entry, reader, allowedTables, statement -> {
                try
                {
                    if (batchInsert && statement.getType() == Type.INSERT)
                    {
                        batch.add(statement.render());
                        if (batch.size() >= INSERT_BATCH_SIZE)
                        {
                            flushBatch(stmt, batch);
                        }
                    }
                    else
                    {
                        stmt.execute(statement.render());
                    }
                }
                catch (SQLException e)
                {
                    throw new ServiceException("还原执行失败：" + e.getMessage());
                }
            });
        }
        catch (IOException e)
        {
            throw new ServiceException("备份包文件读取失败: " + entry);
        }
        if (batch != null)
        {
            flushBatch(stmt, batch);
        }
    }

    /** INSERT 批发送 */
    private void flushBatch(Statement stmt, List<String> batch) throws SQLException
    {
        if (batch.isEmpty())
        {
            return;
        }
        for (String sql : batch)
        {
            stmt.addBatch(sql);
        }
        stmt.executeBatch();
        stmt.clearBatch();
        batch.clear();
    }

    /**
     * 两段式文件替换（KTD6）：三目录 移旧至 staging 临时目录 → 从解压目录拷新；
     * 回滚依赖 {@link #rollbackStaging()} 按目录存在性独立回移。
     */
    private void swapFileDirs(ValidatedPackage pkg)
    {
        assertFileDirsAvailable();
        Path profile = Paths.get(RuoYiConfig.getProfile());
        Path filesRoot = pkg.extractDir.resolve("files");
        Path stagingRoot = Paths.get(RuoYiConfig.getBackupPath()).resolve(STAGING_DIR)
                .resolve("swap-" + System.nanoTime());
        try
        {
            for (String dir : FILE_DIRS)
            {
                Path oldDir = profile.resolve(dir);
                if (Files.exists(oldDir))
                {
                    Files.createDirectories(stagingRoot);
                    moveDirectory(oldDir, stagingRoot.resolve(dir));
                }
                Path freshDir = filesRoot.resolve(dir);
                if (Files.exists(freshDir))
                {
                    moveDirectory(freshDir, profile.resolve(dir));
                }
            }
        }
        catch (IOException e)
        {
            log.error("[RESTORE] 文件替换失败", e);
            throw new ServiceException("还原文件替换失败：" + e.getMessage());
        }
    }

    /**
     * staging 回移（KTD6 可即时调用的内部方法，运行期失败与启动兜底共用）：
     * 按 staging/&lt;ts&gt;/&lt;dir&gt; 目录存在性回移 profile 三目录（存在即视为未完成
     * swap 的旧目录）。回移失败立即重试，重试耗尽告警并置三目录不可用标志（拒绝后续
     * 还原文件替换）；回移全部成功的 swap 目录删壳，仍含数据的保留待下次回移。
     */
    void rollbackStaging()
    {
        Path stagingRoot = Paths.get(RuoYiConfig.getBackupPath()).resolve(STAGING_DIR);
        if (!Files.isDirectory(stagingRoot))
        {
            return;
        }
        List<Path> swapDirs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(stagingRoot))
        {
            stream.forEach(swapDirs::add);
        }
        catch (IOException e)
        {
            log.warn("[RESTORE] staging 目录读取失败", e);
            return;
        }
        Path profile = Paths.get(RuoYiConfig.getProfile());
        for (Path swapDir : swapDirs)
        {
            if (!Files.isDirectory(swapDir))
            {
                continue;
            }
            boolean allMoved = true;
            for (String dir : FILE_DIRS)
            {
                Path source = swapDir.resolve(dir);
                if (!Files.isDirectory(source))
                {
                    continue;
                }
                Path target = profile.resolve(dir);
                boolean moved = false;
                IOException lastFailure = null;
                for (int attempt = 1; attempt <= ROLLBACK_ATTEMPTS && !moved; attempt++)
                {
                    if (attempt > 1)
                    {
                        try
                        {
                            Thread.sleep(ROLLBACK_RETRY_INTERVAL_MS);
                        }
                        catch (InterruptedException ie)
                        {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    try
                    {
                        if (Files.exists(target))
                        {
                            deleteTree(target);
                        }
                        moveForRollback(source, target);
                        moved = true;
                    }
                    catch (IOException e)
                    {
                        lastFailure = e;
                        log.warn("[RESTORE] staging 目录回移失败（第{}次尝试）: {}", attempt, source, e);
                    }
                }
                if (!moved)
                {
                    log.error("[RESTORE] 告警：staging 目录 {} 回移重试耗尽仍失败，用户文件三目录已标记不可用"
                            + "（拒绝后续还原文件替换），请人工处理", source, lastFailure);
                    FILE_DIRS_UNAVAILABLE.set(true);
                    allMoved = false;
                }
            }
            if (allMoved)
            {
                deleteTree(swapDir);
            }
        }
    }

    /**
     * 回移用目录移动（重试的原子操作单元，测试可覆盖注入 IO 失败）
     */
    protected void moveForRollback(Path source, Path target) throws IOException
    {
        moveDirectory(source, target);
    }

    /**
     * 目录移动：同盘原子 rename；跨文件系统降级为递归复制后删除源
     */
    private void moveDirectory(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target);
        }
        catch (IOException e)
        {
            copyTree(source, target);
            deleteTree(source);
        }
    }

    /** 递归复制目录树 */
    private static void copyTree(Path source, Path target) throws IOException
    {
        try (Stream<Path> walk = Files.walk(source))
        {
            for (Path p : (Iterable<Path>) walk::iterator)
            {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p))
                {
                    Files.createDirectories(dest);
                }
                else
                {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 递归删除目录树（尽力删除，失败仅记日志；P2 整体熔断——IOException|RuntimeException 均不外抛） */
    private static void deleteTree(Path path)
    {
        try
        {
            if (!Files.exists(path))
            {
                return;
            }
            try (Stream<Path> walk = Files.walk(path))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try
                    {
                        Files.deleteIfExists(p);
                    }
                    catch (IOException e)
                    {
                        log.warn("[RESTORE] 临时文件删除失败 {}", p);
                    }
                });
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("[RESTORE] 目录删除失败（best-effort） {}", path, e);
        }
    }

    /**
     * 文件三目录可用性检查：不可用（回移失败残留）时拒绝还原文件替换
     */
    static void assertFileDirsAvailable()
    {
        if (FILE_DIRS_UNAVAILABLE.get())
        {
            throw new ServiceException("用户文件目录不可用（存在未完成的回滚），请人工处理后再试");
        }
    }

    /** 文件三目录是否不可用（测试断言用） */
    public static boolean isFileDirsUnavailable()
    {
        return FILE_DIRS_UNAVAILABLE.get();
    }

    /** 复位三目录不可用标志（测试隔离用） */
    static void resetFileDirsUnavailable()
    {
        FILE_DIRS_UNAVAILABLE.set(false);
    }

    /** 还原不一致标志是否置位（测试断言与运维观测用） */
    public static boolean isRestoreInconsistent()
    {
        return RESTORE_INCONSISTENT.get();
    }

    /** 复位还原不一致标志（测试隔离用） */
    static void resetRestoreInconsistent()
    {
        RESTORE_INCONSISTENT.set(false);
    }

    /**
     * P1：写崩溃自愈 marker（restore 执行阶段开始——快照生成成功后、SQL 执行前）。
     * 写失败仅告警不阻断还原（进程内失败恢复不依赖 marker）。
     */
    private void writeRestoreMarker()
    {
        try
        {
            Files.write(Paths.get(RuoYiConfig.getBackupPath()).resolve(RESTORE_MARKER_FILENAME),
                    Instant.now().toString().getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            log.warn("[RESTORE] 崩溃自愈 marker 写入失败（best-effort）", e);
        }
    }

    /** P1：删除崩溃自愈 marker（还原成功或回放成功后调用；best-effort） */
    private void deleteRestoreMarker()
    {
        try
        {
            Files.deleteIfExists(Paths.get(RuoYiConfig.getBackupPath()).resolve(RESTORE_MARKER_FILENAME));
        }
        catch (IOException e)
        {
            log.warn("[RESTORE] 崩溃自愈 marker 删除失败（best-effort）", e);
        }
    }

    /**
     * 还原成功后清理 staging（旧三目录已完成替换使命，直接删除）
     */
    private void cleanupStagingOnSuccess()
    {
        Path stagingRoot = Paths.get(RuoYiConfig.getBackupPath()).resolve(STAGING_DIR);
        if (!Files.isDirectory(stagingRoot))
        {
            return;
        }
        List<Path> swapDirs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(stagingRoot))
        {
            stream.forEach(swapDirs::add);
        }
        catch (IOException e)
        {
            log.warn("[RESTORE] staging 清理失败", e);
            return;
        }
        for (Path swapDir : swapDirs)
        {
            deleteTree(swapDir);
        }
    }

    /**
     * 现库表结构指纹（KTD2 两侧同算法，与 U1 导出侧共用 {@link #fingerprintLine} 素材格式）
     */
    private String computeCurrentFingerprint(Connection conn) throws SQLException
    {
        String catalog = conn.getCatalog();
        List<String> fingerprintLines = new ArrayList<>();
        for (String table : listTables(conn, catalog))
        {
            for (String[] column : tableColumns(conn, catalog, table))
            {
                fingerprintLines.add(fingerprintLine(table, column));
            }
        }
        return computeFingerprint(fingerprintLines);
    }

    /**
     * 现库 sys_user 差异素材：user_id → password 哈希（值不落日志/输出）
     */
    private void collectUserDifference(Connection conn, ValidatedPackage pkg) throws SQLException
    {
        Map<String, String> currentHashes = new HashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT user_id, password FROM sys_user"))
        {
            while (rs.next())
            {
                Object userId = rs.getObject(1);
                Object password = rs.getObject(2);
                // NULL 密码以 null 值承载（与包内收集口径一致）
                currentHashes.put(String.valueOf(userId),
                        password == null ? null : sha256Hex(String.valueOf(password)));
            }
        }
        pkg.currentUserHashes = currentHashes;
    }

    /**
     * 包内 sys_user 账号收集：账号数、user_id 集合（会话判定）、password 哈希（差异比对）
     */
    private void collectPackageUserStats(ValidatedPackage pkg, ParsedStatement statement)
    {
        if (!"sys_user".equalsIgnoreCase(statement.getTable()))
        {
            return;
        }
        pkg.userCount++;
        String userId = statement.getValueOfColumn("user_id");
        if (userId == null)
        {
            return;
        }
        pkg.userIds.add(userId);
        String password = statement.getValueOfColumn("password");
        pkg.packageUserHashes.put(userId, password == null ? null : sha256Hex(password));
    }

    /** SHA-256 哈希（十六进制），password 差异比对用（哈希值不进任何输出；P15 共用 toHex） */
    private static String sha256Hex(String value)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new ServiceException("备份失败：SHA-256 不可用");
        }
    }

    /**
     * 还原成功后重建字典/参数缓存（R8；测试环境服务缺省时跳过）
     */
    private void rebuildCaches()
    {
        if (dictTypeService != null)
        {
            dictTypeService.resetDictCache();
        }
        if (configService != null)
        {
            configService.resetConfigCache();
        }
    }

    /**
     * 会话判定（R8）：包内 sys_user 含当前 userId → 免重登；否则需重登。
     * 无登录上下文（agent/内部调用）保守判定需重登。
     */
    private boolean resolveReloginRequired(ValidatedPackage pkg)
    {
        try
        {
            String currentUserId = String.valueOf(SecurityUtils.getUserId());
            return !pkg.userIds.contains(currentUserId);
        }
        catch (Exception e)
        {
            return true;
        }
    }

    /**
     * 组装校验/执行结果（预览不判定会话）
     */
    private RestoreResult buildResult(ValidatedPackage pkg, boolean executed)
    {
        RestoreResult result = new RestoreResult();
        result.setTableCount(pkg.tables.size());
        result.setRecordCount(pkg.recordCount);
        result.setFileCount(pkg.fileCount);
        result.setUserCount(pkg.userCount);
        result.setCredentialChanged(pkg.credentialChanged);
        result.setExecuted(executed);
        result.setReloginRequired(executed && resolveReloginRequired(pkg));
        return result;
    }

    /**
     * 已校验备份包（解压目录 + 表清单 + 统计与 sys_user 差异素材）
     */
    private static final class ValidatedPackage
    {
        /** 解压临时目录（restore 持有期间用，finally 删除） */
        Path extractDir;

        /** manifest 表清单（还原目标白名单基准） */
        List<String> tables;

        /** files/ 文件数 */
        long fileCount;

        /** INSERT 总行数 */
        long recordCount;

        /** 包内 sys_user 账号数 */
        int userCount;

        /** sys_user 凭据/账号差异标记（仅布尔输出） */
        boolean credentialChanged;

        /** 包内 sys_user 的 user_id 集合（会话判定） */
        Set<String> userIds = new LinkedHashSet<>();

        /** 包内 user_id → password 哈希（null=密码 NULL；不进输出） */
        Map<String, String> packageUserHashes = new HashMap<>();

        /** 现库 user_id → password 哈希（不进输出） */
        Map<String, String> currentUserHashes = new HashMap<>();

        /** 表清单小写集合（白名单表名比对） */
        Set<String> allowedTableSet()
        {
            Set<String> allowed = new HashSet<>();
            for (String table : tables)
            {
                allowed.add(table.toLowerCase(Locale.ROOT));
            }
            return allowed;
        }
    }
}
