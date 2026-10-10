package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.NoteNotelink;
import org.apache.ibatis.annotations.Param;

/**
 * 笔记链接Mapper接口
 *
 * @author liuyanghe
 * @date 2026-02-08
 */
public interface NoteNotelinkMapper
{
    /**
     * 查询笔记链接
     *
     * @param id 笔记链接主键
     * @return 笔记链接
     */
    public NoteNotelink selectNoteNotelinkById(Long id);

    /**
     * 查询笔记链接列表
     *
     * @param noteNotelink 笔记链接
     * @return 笔记链接集合
     */
    public List<NoteNotelink> selectNoteNotelinkList(NoteNotelink noteNotelink);

    /**
     * U2: 按 cell 聚合查询(供 R6 单元格展示)
     * 按 (linkColumnId + linkItemId) 查所有 NoteNotelink,按 id 升序返回
     *
     * @param linkColumnId 关联列 id(发起关联的语义关联列)
     * @param linkItemId   关联单元格 id(NoteDwtableItem.id)
     * @return 笔记链接集合(按 id 升序)
     */
    public List<NoteNotelink> selectNoteNotelinkByCell(@Param("linkColumnId") Long linkColumnId,
                                                       @Param("linkItemId") Long linkItemId);

    /**
     * U5: 按 noteId 查询引用列表(供 R9 追溯面板)
     * 查所有引用该笔记的 NoteNotelink,按 id 升序返回
     *
     * @param noteId 笔记 id
     * @return 笔记链接集合(按 id 升序)
     */
    public List<NoteNotelink> selectNoteNotelinkByNoteId(Long noteId);

    /**
     * 新增笔记链接
     *
     * @param noteNotelink 笔记链接
     * @return 结果
     */
    public int insertNoteNotelink(NoteNotelink noteNotelink);

    /**
     * 修改笔记链接
     *
     * @param noteNotelink 笔记链接
     * @return 结果
     */
    public int updateNoteNotelink(NoteNotelink noteNotelink);

    /**
     * U4: 25 列(语义关联)重建——按主键 upsert 行内坐标与核心字段(供 Excel 导入事务段调用)
     * <p>
     * 重建时序为"先清旧行(deleteNoteNotelinkByColumnId)再逐条目恢复"：本语句显式携带原主键
     * insert，行已不存在时以<b>原行 id 恢复插入</b>(行 id 保留——源笔记正文锚点 data-link-id
     * 不断链)；行仍存在(同一条目被重复引用等场景)时按主键 update 行内坐标与核心字段。
     *
     * @param noteNotelink 笔记链接(id 必填=原行 id；核心字段来自旧行捕获、行内坐标指向本表新记录)
     * @return 受影响行数(insert 计 1 / update 计 2，MySQL ON DUPLICATE KEY UPDATE 语义)
     */
    public int updateNoteNotelinkCoordinates(NoteNotelink noteNotelink);

    /**
     * 批量删除笔记链接
     *
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteNotelinkByIds(Long[] ids);

    /**
     * U2: 按 linkColumnId 级联删除(供 deleteDataWhenLink 调用)
     * 删除语义关联列时清理孤儿 NoteNotelink 记录
     *
     * @param linkColumnId 关联列 id
     * @return 受影响行数
     */
    public int deleteNoteNotelinkByColumnId(Long linkColumnId);
}
