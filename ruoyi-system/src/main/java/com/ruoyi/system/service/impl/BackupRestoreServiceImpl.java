package com.ruoyi.system.service.impl;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.BackupLockUtil;
import com.ruoyi.system.service.IBackupRestoreService;

/**
 * 整机迁移备份/还原服务实现（U1：备份侧）。
 * <p>
 * 备份 = 全库表结构+数据（JDBC 逻辑导出，KTD1）+ profile 下 upload/avatar/import
 * 三目录 + manifest 清单，经 {@link ZipOutputStream} 流式写入 backupPath 落盘
 * （KTD4：独立于 profile 的专有目录，分槽位各保留最近 1 份）。download 目录与
 * backupPath 自身不入包（R2/R3）。触发级写锁（KTD3）：backup() 为入口检查薄壳，
 * exportInternal() 不检锁——供 restore 生成还原前快照复用（重入拆分）。
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

    /** 触发级写锁标志（KTD3）：备份/还原共用，消费者经 {@link BackupLockUtil} 检查同一标志 */
    private static final java.util.concurrent.atomic.AtomicBoolean WRITE_LOCK =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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

    /** 入包的用户文件目录（profile 下；download 目录不入包，R2/R3） */
    private static final String[] FILE_DIRS = {"upload", "avatar", "import"};

    /** 文件流式拷贝缓冲区大小 */
    private static final int COPY_BUFFER_SIZE = 8192;

    /**
     * 数据源（druid 动态数据源 master）：元数据与导出直取原生连接，不经过 MyBatis
     */
    @Autowired
    private DataSource dataSource;

    /**
     * 尝试获取触发级写锁（KTD3）：备份/还原共用，任一持有时其他触发被拒绝。
     *
     * @return true=获取成功（调用方 finally 释放）；false=已有备份/还原进行中
     */
    public static boolean tryAcquireBackupLock()
    {
        return WRITE_LOCK.compareAndSet(false, true);
    }

    /**
     * 释放触发级写锁
     */
    public static void releaseLock()
    {
        WRITE_LOCK.set(false);
    }

    /**
     * 写锁是否被持有（消费者检查与测试用）
     */
    public static boolean isWriteLocked()
    {
        return WRITE_LOCK.get();
    }

    /**
     * 触发整机备份（KTD3 入口薄壳）：加写锁 → exportInternal 导出落盘备份槽位 → finally 解锁。
     *
     * @return 备份包文件（backupPath/backup.zip）
     */
    @Override
    public File backup()
    {
        if (!tryAcquireBackupLock())
        {
            throw new ServiceException(BackupLockUtil.LOCKED_MESSAGE);
        }
        try
        {
            return exportInternal(BACKUP_SLOT);
        }
        finally
        {
            releaseLock();
        }
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
                        fingerprintLines.add(table + "\t" + col[0] + "\t" + col[1]);
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
     * 取表列清单（保持 ordinal 顺序）：每项为 [列名, 列类型名]，供 INSERT 列段与指纹使用。
     */
    private List<String[]> tableColumns(Connection conn, String catalog, String table) throws SQLException
    {
        List<String[]> columns = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getColumns(catalog, null, table, "%"))
        {
            while (rs.next())
            {
                columns.add(new String[] {rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME")});
            }
        }
        return columns;
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
        if (value instanceof java.util.Date)
        {
            // 驱动返回裸 java.util.Date 的兜底：按 Timestamp 语义格式化
            return "'" + new Timestamp(((java.util.Date) value).getTime()).toString() + "'";
        }
        // 字符串与其余类型（含 JSON 列）：字符串转义（罕见类型的回退路径）
        return escapeString(value.toString());
    }

    /**
     * MySQL 语义字符串转义（等价 mysql_real_escape_string 的导出子集）：
     * 单引号→''、反斜杠与控制字符→反斜杠序列，包裹为 '...' 字面量。
     */
    private static String escapeString(String value)
    {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('\'');
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            switch (c)
            {
                case '\0':
                    sb.append("\\0");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case 0x1a:
                    sb.append("\\Z");
                    break;
                case '\'':
                    sb.append("''");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                default:
                    sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    /**
     * 二进制列编码为 MySQL hex 字面量（0x...）
     */
    private static String hexLiteral(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder(bytes.length * 2 + 2);
        sb.append("0x");
        for (byte b : bytes)
        {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
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
     * 表结构指纹（KTD2）：全部（表名,列名,列类型）元组排序后逐行 SHA-256——
     * 排序保证同一库指纹稳定（与列定义顺序无关），增删列/改类型即变化。
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
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest())
            {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
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
}
