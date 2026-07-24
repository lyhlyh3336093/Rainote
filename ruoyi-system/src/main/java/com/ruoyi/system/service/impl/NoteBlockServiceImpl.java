package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.ruoyi.common.enums.BlockType;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.domain.vo.NoteBlockVo;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.service.INoteBlockService;

/**
 * 块元素Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-05-17
 */
@Service
public class NoteBlockServiceImpl implements INoteBlockService 
{
    @Autowired
    private NoteBlockMapper noteBlockMapper;


    @Autowired
    private NoteDwtableMapper noteDwtableMapper;
    @Autowired
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Autowired
    private NoteViewMapper noteViewMapper;

    @Autowired
    private NoteColumnMapper noteColumnMapper;

    @Autowired
    private NoteRecordMapper noteRecordMapper;

    /**
     * 查询块元素
     * 
     * @param id 块元素主键
     * @return 块元素
     */
    @Override
    public NoteBlock selectNoteBlockById(Long id)
    {
        return noteBlockMapper.selectNoteBlockById(id);
    }

    /**
     * 查询块元素列表
     * 
     * @param noteBlock 块元素
     * @return 块元素
     */
    @Override
    public List<NoteBlock> selectNoteBlockList(NoteBlock noteBlock)
    {
        return noteBlockMapper.selectNoteBlockList(noteBlock);
    }

    /**
     * 新增块元素
     * 
     * @param noteBlock 块元素
     * @return 结果
     */
    @Override
    public HashMap<String,Object> insertNoteBlock(NoteBlock noteBlock)
    {

//        NoteBlock noteBlock = new NoteBlock();
//        noteBlock.setBlockType(noteBlockVo.getBlockType());
//        noteBlock.setProperty(noteBlockVo.getProperty().toString());
//        noteBlock.setParentId(noteBlockVo.getParentId());
//        noteBlock.setChildId(noteBlockVo.getChildId());


        HashMap<String,Object> resultMap = new HashMap<String,Object>();
        int result = noteBlockMapper.insertNoteBlock(noteBlock);
        if(result == 1){
            result = noteBlock.getId().intValue();
            resultMap.put("blockId",result);
        }


        //如果是多维表格的话，做特殊处理
        String enumCode = BlockType.BITABLE.getCode();
        if(noteBlock.getBlockType().toString().equals(BlockType.BITABLE.getCode())){
            //先新建数据表，再新建表格视图，再新建五个列
            NoteDwtable noteDwtable = new NoteDwtable();
            noteDwtable.setNoteId(noteBlock.getParentId());
            noteDwtable.setName("块级多维表格");
            noteDwtableMapper.insertNoteDwtable(noteDwtable);
            resultMap.put("dwtableId",noteDwtable.getId());
            //创建视图
            NoteView noteView = new NoteView();
            noteView.setDwtableId(noteDwtable.getId());
            noteView.setType(1L);
            noteView.setName("表格视图");
            noteViewMapper.insertNoteView(noteView);
            resultMap.put("viewId",noteView.getId());
            //默认五个列
            NoteColumn column1 = new NoteColumn();
            column1.setType(1L);
            column1.setName("多行文本");
            column1.setIsShow(0L);
            column1.setDwtableId(noteView.getDwtableId());
            noteColumnMapper.insertNoteColumn(column1);
            NoteColumn column3 = new NoteColumn();
            column3.setType(3L);
            column3.setName("单选");
            column3.setIsShow(0L);
            column3.setDwtableId(noteView.getDwtableId());
            noteColumnMapper.insertNoteColumn(column3);
            NoteColumn column5 = new NoteColumn();
            column5.setType(5L);
            column5.setName("日期");
            column5.setIsShow(0L);
            column5.setDwtableId(noteView.getDwtableId());
            noteColumnMapper.insertNoteColumn(column5);
            NoteColumn column4 = new NoteColumn();
            column4.setType(4L);
            column4.setName("多选");
            column4.setIsShow(0L);
            column4.setDwtableId(noteView.getDwtableId());
            noteColumnMapper.insertNoteColumn(column4);
            NoteColumn column17 = new NoteColumn();
            column17.setType(17L);
            column17.setName("附件");
            column17.setIsShow(0L);
            column17.setDwtableId(noteView.getDwtableId());
            noteColumnMapper.insertNoteColumn(column17);

        }


        return resultMap;
    }

    /**
     * 修改块元素
     * 
     * @param noteBlock 块元素
     * @return 结果
     */
    @Override
    public int updateNoteBlock(NoteBlock noteBlock)
    {
        noteBlockMapper.updateNoteBlock(noteBlock);
        return 1;
    }

    /**
     * 批量删除块元素
     * 
     * @param ids 需要删除的块元素主键
     * @return 结果
     */
    @Override
    public int deleteNoteBlockByIds(String[] ids)
    {
        return noteBlockMapper.deleteNoteBlockByIds(ids);
    }

    /**
     * 删除块元素信息
     * 
     * @param id 块元素主键
     * @return 结果
     */
    @Override
    public int deleteNoteBlockById(Long id)
    {
        return noteBlockMapper.deleteNoteBlockById(id);
    }



    /**
     * 对多维表格进行双向关联
     *
     * @param noteBlockVo 被关联的行id
     * @return 结果
     */
    @Override
    public Long linkToDwtable(NoteBlockVo noteBlockVo) {
        //要对多维表格进行双向关联,首先要在多维表格的列里面加一个新的列.
        //拿到dwtable,新增一个column,类型是25,默认列名为"关联笔记",
        //再在这个列下面创建所有record的item,具体字段内容都用默认值
        //再根据关联的具体record,去对应的item里面加上发起关联的笔记名
        //点击会跳转到笔记界面,也就是说需要笔记的id给到前端.
        NoteRecord noteRecord = noteRecordMapper.selectNoteRecordById(noteBlockVo.getRecordId());
//        NoteDwtable noteDwtable = noteDwtableMapper.selectNoteDwtableById(noteRecord.getDwtableId());
        //创建新的列
        NoteColumn noteColumn = new NoteColumn();
        noteColumn.setDwtableId(noteRecord.getDwtableId());
        noteColumn.setSort(20L);
        noteColumn.setName("关联笔记");
        noteColumn.setType(25L);
        noteColumn.setIsShow(0L);
        noteColumn.setProperty(noteBlockVo.getProperty().toString());
        noteColumnMapper.insertNoteColumn(noteColumn);

        NoteRecordVo queryRecord = new NoteRecordVo();
        queryRecord.setDwtableId(noteRecord.getDwtableId());
        List<NoteRecord> noteRecordList = noteRecordMapper.selectNoteRecordList(queryRecord);
        if(noteRecordList.size()>0){
            for (NoteRecord record:noteRecordList) {
                //给这些记录加一个新的item,对应column是上面新加的关联笔记列,默认为空,遇到关联的那一条给他加上内容
                NoteDwtableItem item = new NoteDwtableItem();
                item.setRecordId(record.getId());
//                item.setValue("");
                item.setColumnId(noteColumn.getId());
                item.setDwtId(record.getDwtableId());
                if(record.getId().equals(noteBlockVo.getRecordId())){
//                    item.setValue(noteBlockVo.getContextText());
                    item.setValue(noteBlockVo.getNoteTitle().isEmpty()?noteBlockVo.getContextText():noteBlockVo.getNoteTitle());
                    item.setLinkBlockId(noteBlockVo.getBlockId());
                    item.setLinkNoteId(noteBlockVo.getSourceId());
                }
                noteDwtableItemMapper.insertNoteDwtableItem(item);

            }

        }

        //看看有没有正确插入进去
        List<NoteDwtableItem> checkItemList = new ArrayList<>();
        NoteDwtableItem queryItem = new NoteDwtableItem();
        queryItem.setColumnId(noteColumn.getId());
        checkItemList=noteDwtableItemMapper.selectNoteDwtableItemList(queryItem);
        if(checkItemList.size()>0){
            return noteColumn.getId();
        }
        return null;
    }


    /**
     * 对多维表格进行双向关联
     *
     * @param noteBlockVo 被关联的行id
     * @return 结果
     */
    @Override
    public int removeLink(NoteBlockVo noteBlockVo) {
        //取消双向关联
        NoteRecord noteRecord = noteRecordMapper.selectNoteRecordById(noteBlockVo.getRecordId());
        //取消关联一个是要删除掉对应的笔记链接列,再吧列下面的所有item删掉,

        return 0;
    }
}
