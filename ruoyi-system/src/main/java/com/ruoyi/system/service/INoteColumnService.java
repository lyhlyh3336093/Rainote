package com.ruoyi.system.service;

import java.util.List;
import java.util.Map;

import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.vo.NoteColumnVo;

/**
 * 列信息Service接口
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
public interface INoteColumnService 
{
    /**
     * 查询列信息
     * 
     * @param id 列信息主键
     * @return 列信息
     */
    public NoteColumn selectNoteColumnById(Long id);

    /**
     * 查询列信息列表
     *
     * @param noteColumn 列信息
     * @return 列信息集合
     */
    public List<NoteColumn> selectNoteColumnList(NoteColumn noteColumn);

    /**
     * 根据数据表id查询数据表下的所有双向链接列
     *
     * @param dwtableId 数据表id
     * @return 列信息集合
     */
    public List<NoteColumn> selectNoteDoubleLinkColumnList(Long dwtableId);

    /**
     * 新增列信息
     * 
     * @param noteColumnvo 列信息
     * @return 结果
     */
    public int insertNoteColumn(NoteColumnVo noteColumnvo);

    /**
     * 修改列信息
     * 
     * @param noteColumnvo 列信息
     * @return 结果
     */
    public int updateNoteColumn(NoteColumnVo noteColumnvo);

    /**
     * 修改排序
     *
     * @param sorts 排序信息
     * @return 结果
     */
    public int updateSort(List<Map<String, Object>> sorts);

    /**
     * 批量删除列信息
     * 
     * @param ids 需要删除的列信息主键集合
     * @return 结果
     */
    public int deleteNoteColumnByIds(String[] ids);

    /**
     * 删除列信息信息
     *
     * @param id 列信息主键
     * @return 结果
     */
    public int deleteNoteColumnById(Long id);

    /**
     * 切换指定lookup列(type=26)的去重开关，并重算该列存储值及关联的集合运算列。
     *
     * @param columnId lookup列ID
     * @return 结果
     */
    public int deduplicate(Long columnId);
}
