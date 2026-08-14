package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

import com.ruoyi.system.domain.dto.ExportColumn;
import com.ruoyi.system.domain.dto.ExportFile;
import com.ruoyi.system.domain.dto.ExportMatrix;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NoteDwtableSqlRenderer} 单元测试。
 * <p>
 * 覆盖 AE8 超限表分片、空表、类型映射、SQL 注入防护、标识符合法化。
 *
 * @author ruoyi
 */
class NoteDwtableSqlRendererTest
{
    private final NoteDwtableSqlRenderer renderer = new NoteDwtableSqlRenderer();

    /**
     * 构建测试矩阵：1 记录 id 列 + 1 文本列。
     */
    private ExportMatrix buildMatrix(String tableName, int rowCount)
    {
        ExportMatrix matrix = new ExportMatrix();
        matrix.setDwtableId(1L);
        matrix.setTableName(tableName);
        List<ExportColumn> columns = new ArrayList<>();
        columns.add(new ExportColumn(null, "record_id", null, false, true));
        columns.add(new ExportColumn(10L, "name", 1L, false, false));
        matrix.setColumns(columns);
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i <= rowCount; i++)
        {
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(i));
            row.add("value" + i);
            rows.add(row);
        }
        matrix.setRows(rows);
        return matrix;
    }

    @Test
    void render_normalTable_singleFile()
    {
        ExportMatrix matrix = buildMatrix("T1", 100);
        List<ExportFile> files = renderer.render(matrix);
        assertEquals(1, files.size());
        assertEquals("T1_1.sql", files.get(0).getFileName());
        String sql = new String(files.get(0).getContent(), StandardCharsets.UTF_8);
        // 含 CREATE TABLE + INSERT
        assertTrue(sql.contains("CREATE TABLE"));
        assertTrue(sql.contains("INSERT INTO"));
        // 记录 id 列为 BIGINT
        assertTrue(sql.contains("BIGINT"));
    }

    @Test
    void render_overLimitTable_multipleShards()
    {
        ExportMatrix matrix = buildMatrix("T", 5000);
        List<ExportFile> files = renderer.render(matrix);
        // 5000 行 / 2000 = 3 片
        assertEquals(3, files.size());
        assertEquals("T_1_p1.sql", files.get(0).getFileName());
        assertEquals("T_1_p2.sql", files.get(1).getFileName());
        assertEquals("T_1_p3.sql", files.get(2).getFileName());
        // 首片含 CREATE TABLE
        String p1 = new String(files.get(0).getContent(), StandardCharsets.UTF_8);
        assertTrue(p1.contains("CREATE TABLE"));
        assertTrue(p1.contains("INSERT INTO"));
        // 后续片不含 CREATE TABLE
        String p2 = new String(files.get(1).getContent(), StandardCharsets.UTF_8);
        assertFalse(p2.contains("CREATE TABLE"));
        assertTrue(p2.contains("INSERT INTO"));
    }

    @Test
    void render_emptyTable_createTableNoInsert()
    {
        ExportMatrix matrix = buildMatrix("empty", 0);
        List<ExportFile> files = renderer.render(matrix);
        assertEquals(1, files.size());
        String sql = new String(files.get(0).getContent(), StandardCharsets.UTF_8);
        assertTrue(sql.contains("CREATE TABLE"));
        assertFalse(sql.contains("INSERT INTO"));
    }

    @Test
    void render_sqlInjection_valueEscaped()
    {
        ExportMatrix matrix = new ExportMatrix();
        matrix.setDwtableId(1L);
        matrix.setTableName("T1");
        List<ExportColumn> columns = new ArrayList<>();
        columns.add(new ExportColumn(null, "record_id", null, false, true));
        columns.add(new ExportColumn(10L, "name", 1L, false, false));
        matrix.setColumns(columns);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        row.add("1");
        row.add("'; DROP TABLE T1; --");
        rows.add(row);
        matrix.setRows(rows);

        List<ExportFile> files = renderer.render(matrix);
        String sql = new String(files.get(0).getContent(), StandardCharsets.UTF_8);
        // 单引号被转义为 ''，整个值被包裹在单引号内成为字面量
        // 原始值 '; DROP TABLE T1; -- 转义后为 '''; DROP TABLE T1; --'
        assertTrue(sql.contains("'''; DROP TABLE T1; --'"));
        // 不应出现裸 DROP TABLE 作为独立语句（在 SQL 文本中被引号包裹为字面量）
        // 验证：SQL 中只有一条 INSERT 语句（没有额外的 DROP）
        long dropCount = java.util.Arrays.stream(sql.split("\n"))
                .filter(l -> l.trim().toUpperCase().startsWith("DROP TABLE")).count();
        assertEquals(0, dropCount);
    }

    @Test
    void render_reservedWordColumn_backtickQuoted()
    {
        ExportMatrix matrix = new ExportMatrix();
        matrix.setDwtableId(1L);
        matrix.setTableName("T1");
        List<ExportColumn> columns = new ArrayList<>();
        columns.add(new ExportColumn(null, "record_id", null, false, true));
        columns.add(new ExportColumn(10L, "order", 1L, false, false));
        matrix.setColumns(columns);
        matrix.setRows(new ArrayList<>());

        List<ExportFile> files = renderer.render(matrix);
        String sql = new String(files.get(0).getContent(), StandardCharsets.UTF_8);
        // 保留字 order 应被反引号包裹
        assertTrue(sql.contains("`order`"));
    }

    @Test
    void render_numberColumn_valueValidated()
    {
        ExportMatrix matrix = new ExportMatrix();
        matrix.setDwtableId(1L);
        matrix.setTableName("T1");
        List<ExportColumn> columns = new ArrayList<>();
        columns.add(new ExportColumn(null, "record_id", null, false, true));
        columns.add(new ExportColumn(10L, "amount", 2L, false, false));
        matrix.setColumns(columns);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        row.add("1");
        row.add("1; DROP TABLE--"); // 注入向量
        rows.add(row);
        matrix.setRows(rows);

        List<ExportFile> files = renderer.render(matrix);
        String sql = new String(files.get(0).getContent(), StandardCharsets.UTF_8);
        // 数字列值校验失败 → 写 NULL
        assertTrue(sql.contains("NULL"));
        // 不应出现注入的 DROP TABLE 作为独立语句
        assertFalse(sql.contains("1; DROP TABLE--"));
    }
}
