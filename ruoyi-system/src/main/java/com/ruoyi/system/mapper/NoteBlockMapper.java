package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.NoteBlock;
import com.ruoyi.system.domain.NoteMeta;

/**
 * 块元素Mapper接口
 * 
 * @author liuyanghe
 * @date 2023-05-17
 */
public interface NoteBlockMapper 
{
    /**
     * 查询块元素
     * 
     * @param id 块元素主键
     * @return 块元素
     */
    public NoteBlock selectNoteBlockById(Long id);


    /**
     * 清空所有
     *
     */
    public int removeAll();

    /**
     * 查询块元素列表
     * 
     * @param noteBlock 块元素
     * @return 块元素集合
     */
    public List<NoteBlock> selectNoteBlockList(NoteBlock noteBlock);

    /**
     * 新增块元素
     * 
     * @param noteBlock 块元素
     * @return 结果
     */
    public int insertNoteBlock(NoteBlock noteBlock);

    /**
     * 批量新增块元素
     *
     * @param noteBlocks 块元素集合
     * @return 结果
     */
    public int batchInsertNoteBlock(List<NoteBlock> noteBlocks);

    /**
     * 修改块元素
     * 
     * @param noteBlock 块元素
     * @return 结果
     */
    public int updateNoteBlock(NoteBlock noteBlock);

    /**
     * 删除块元素
     * 
     * @param id 块元素主键
     * @return 结果
     */
    public int deleteNoteBlockById(Long id);

    /**
     * 批量删除块元素
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteNoteBlockByIds(String[] ids);
}
