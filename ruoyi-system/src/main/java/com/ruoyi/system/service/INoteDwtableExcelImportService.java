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
}
