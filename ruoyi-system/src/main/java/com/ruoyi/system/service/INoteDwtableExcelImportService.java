package com.ruoyi.system.service;

import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.system.domain.dto.ExcelImportPrecheckResult;

/**
 * 多维表格 Excel 导入服务接口（两阶段，U4 预检 + U5 导入）。
 * <p>
 * 阶段一（预检）：解析 + 列映射 + 关联文本匹配 + 缺参与影响面汇总，
 * 返回预检清单且不写库（R9/R10/R11/R14/R15/R23/R25）。
 * 阶段二（导入，U5 落地）：携带补参与文件指纹重新上传，服务端重新解析匹配后单事务写入。
 *
 * @author ruoyi
 */
public interface INoteDwtableExcelImportService
{
    /**
     * 阶段一预检：解析 Excel、映射列、匹配关联文本并汇总缺参与影响面，不写任何数据。
     * <p>
     * 调用端（Controller）已完成归属校验、noteId↔dwtableId 匹配断言与空文件检查
     * （照 {@code importData} 模式）；本方法自带目标表存在性与 noteId 匹配的防御性复查。
     * <ul>
     *   <li>缺参三种情形（R10）：缺列/缺值（关联列空单元格除外）/关联未命中，
     *       填充优先级默认值 > 弹框（有默认值的列不进缺参清单）；</li>
     *   <li>关联列 18/21（R14/R15/R23）：被关联表须存在、与目标表同 noteId 且过归属校验，
     *       失败抛 {@link com.ruoyi.common.exception.ServiceException}（含列名与原因，阻断整次预检）；
     *       单元格文本整串优先匹配记录名，未命中再按英文逗号拆分逐个匹配（KTD4）；</li>
     *   <li>预检不触碰任何写路径（零 insert/update）。</li>
     * </ul>
     *
     * @param noteId    目标笔记 id（调用端已校验与 dwtableId 匹配）
     * @param dwtableId 目标多维表格 id
     * @param file      上传的 .xlsx 文件（调用端已校验非空）
     * @param userId    操作用户 id（被关联表归属校验）
     * @return 预检清单（含文件指纹，KTD5）
     * @throws com.ruoyi.common.exception.ServiceException
     *         目标表不匹配/文件损坏或超限/sheet 无匹配/被关联表归属校验失败等（中文消息）
     */
    ExcelImportPrecheckResult precheck(Long noteId, Long dwtableId, MultipartFile file, Long userId);

    /**
     * 阶段二导入写入：携带补参与文件指纹重新上传，服务端重新解析匹配后单事务写入（U5）。
     * <p>
     * 非事务前置段（KTD5）：文件指纹校验（size 优先再 md5，不一致整体拒绝）→
     * 与预检同一私有方法链重新解析/映射/匹配 → params JSON 解析（失败明确拒绝）→
     * 补参服务端校验（R26：relationSelections 的 recordId 须属于该列被关联表且存在[查库]，
     * columnValues 文本补值过 validateCellText 同语义校验）→ 反向漂移 fail-fast
     * （阶段二未命中名 ∉ baseline.missNames、歧义集合与 baseline 不一致、缺参列 ∉
     * baseline.missingColumns、补参 recordId 已不存在——任一命中整体拒绝"数据已变化，请重新预检"）。
     * 补参优先语义（KTD5）：补参覆盖的键以补参为准——recordId=null 显式留空即便重新匹配命中也不建立关联。
     * <p>
     * {@code @Transactional} 写入段（R22，经 Spring 代理加入事务）：
     * <ol>
     *   <li>逐行 NoteRecord insert（自增 id 回填，sort=maxSort+1 递增，name 按首个 type=1 列值派生）；</li>
     *   <li>items 累积（三源合并：Excel 值 &gt; 列默认值 &gt; 用户补参；复选框 true/false → '0'/'1'；
     *       NULL/空 → 空串）≥500 flush（含 link 字段列的批量 insert）；</li>
     *   <li>单选/多选未知选项追加（FOR UPDATE 锁定读改写，每列每次导入上限 100，超限中止回滚）；</li>
     *   <li>items 全量最终 flush（零缓冲不变量）；</li>
     *   <li>双链(21)对称写入（按配对 item 聚合单次 update + FOR UPDATE 锁定 + upsert，
     *       四字段语义对齐 UI 路径并含 KTD3 两处有意修正）；</li>
     *   <li>重算编排（KTD8：目标表全部 lookup 列两步级联 + 被关联表以配对列为源的 lookup/集合运算列 +
     *       目标表直接引用 18/21 列的集合运算列，经 {@code INoteRecordService} 接口调用加入本事务）。</li>
     * </ol>
     * 失败抛 {@link com.ruoyi.common.exception.ServiceException} 整体回滚
     * （含对称写入对被关联表存量 item 的修改与选项追加）。
     *
     * @param noteId     目标笔记 id（调用端已校验与 dwtableId 匹配）
     * @param dwtableId  目标多维表格 id
     * @param file       上传的 .xlsx 文件（调用端已校验非空；须与预检文件同一）
     * @param paramsJson 补参 JSON 字符串（{@link com.ruoyi.system.domain.dto.ExcelImportParams} 契约）
     * @param userId     操作用户 id（被关联表归属校验）
     * @return 成功导入的记录数（recordCount）
     * @throws com.ruoyi.common.exception.ServiceException
     *         指纹不一致/params 非法/漂移/补参校验失败/写入失败等（中文消息，整体回滚）
     */
    int importExcelData(Long noteId, Long dwtableId, MultipartFile file, String paramsJson, Long userId);
}
