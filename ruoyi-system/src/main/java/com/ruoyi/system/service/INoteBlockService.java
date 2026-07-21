package com.ruoyi.system.service;

import java.util.HashMap;
import java.util.List;
import com.ruoyi.system.domain.NoteBlock;
import com.ruoyi.system.domain.vo.NoteBlockVo;

/**
 * 块元素Service接口
 * 
 * @author liuyanghe
 * @date 2023-05-17
 */
public interface INoteBlockService 
{
    /**
     * 查询块元素
     * 
     * @param id 块元素主键
     * @return 块元素
     */
    public NoteBlock selectNoteBlockById(Long id);

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
    public HashMap<String,Object> insertNoteBlock(NoteBlock noteBlock);

    /**
     * 修改块元素
     * 
     * @param noteBlock 块元素
     * @return 结果
     */
    public int updateNoteBlock(NoteBlock noteBlock);

    /**
     * 批量删除块元素
     * 
     * @param ids 需要删除的块元素主键集合
     * @return 结果
     */
    public int deleteNoteBlockByIds(String[] ids);

    /**
     * 删除块元素信息
     * 
     * @param id 块元素主键
     * @return 结果
     */
    public int deleteNoteBlockById(Long id);


    /**
     * 对多维表格进行双向关联
     *
     * @param noteBlockVo
     * @return 结果
     */
    int linkToDwtable(NoteBlockVo noteBlockVo);

    /**
     * 对多维表格取消双向关联
     *
     * @param noteBlockVo
     * @return 结果
     */
    int removeLink(NoteBlockVo noteBlockVo);
}
