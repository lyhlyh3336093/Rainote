package com.ruoyi.system.service.impl;

import java.util.List;

import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.domain.NoteView;
import com.ruoyi.system.service.INoteViewService;

/**
 * 视图Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-04-10
 */
@Service
public class NoteViewServiceImpl implements INoteViewService 
{
    @Autowired
    private NoteViewMapper noteViewMapper;

    @Autowired
    private NoteColumnMapper noteColumnMapper;

    @Autowired
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Autowired
    private NoteDwtableMapper noteDwtableMapper;

    @Autowired
    private NoteRecordMapper noteRecordMapper;
    /**
     * 查询视图
     * 
     * @param id 视图主键
     * @return 视图
     */
    @Override
    public NoteView selectNoteViewById(Long id)
    {
        return noteViewMapper.selectNoteViewById(id);
    }

    /**
     * 查询视图列表
     * 
     * @param noteView 视图
     * @return 视图
     */
    @Override
    public List<NoteView> selectNoteViewList(NoteView noteView)
    {
        return noteViewMapper.selectNoteViewList(noteView);
    }

    /**
     * 新增视图
     * 
     * @param noteView 视图
     * @return 结果
     */
    @Override
    public Long insertNoteView(NoteView noteView)
    {
        //新增视图的时候默认添加几个基础模板列
        //判断是不是第一个视图，如果是就加，不是就不加
        List<NoteView> checkList = noteViewMapper.selectNoteViewListByDwtableId(noteView.getDwtableId());
        if(checkList.size()>0){
            noteViewMapper.insertNoteView(noteView);
        }else{
            noteViewMapper.insertNoteView(noteView);
            NoteColumn column1 = new NoteColumn();
            column1.setType(1L);
            column1.setName("多行文本");
            column1.setIsShow(0L);
            column1.setSort(1L);
            column1.setDwtableId(noteView.getDwtableId());
            noteColumnMapper.insertNoteColumn(column1);
            NoteColumn column3 = new NoteColumn();
            column3.setType(3L);
            column3.setName("单选");
            column3.setIsShow(0L);
            column3.setSort(2L);
            column3.setDwtableId(noteView.getDwtableId());
            noteColumnMapper.insertNoteColumn(column3);
            NoteColumn column5 = new NoteColumn();
            column5.setType(5L);
            column5.setName("日期");
            column5.setIsShow(0L);
            column5.setSort(3L);
            column5.setDwtableId(noteView.getDwtableId());
            noteColumnMapper.insertNoteColumn(column5);
            NoteColumn column4 = new NoteColumn();
            column4.setType(4L);
            column4.setName("多选");
            column4.setIsShow(0L);
            column4.setSort(4L);
            column4.setDwtableId(noteView.getDwtableId());
            noteColumnMapper.insertNoteColumn(column4);
            NoteColumn column17 = new NoteColumn();
            column17.setType(17L);
            column17.setName("附件");
            column17.setIsShow(0L);
            column17.setSort(5L);
            column17.setDwtableId(noteView.getDwtableId());
            noteColumnMapper.insertNoteColumn(column17);
        }
        return noteView.getId();
    }

    /**
     * 修改视图
     * 
     * @param noteView 视图
     * @return 结果
     */
    @Override
    public int updateNoteView(NoteView noteView)
    {
        return noteViewMapper.updateNoteView(noteView);
    }

    /**
     * 批量删除视图
     * 
     * @param ids 需要删除的视图主键
     * @return 结果
     */
    @Override
    public int deleteNoteViewByIds(String[] ids)
    {
        return noteViewMapper.deleteNoteViewByIds(ids);
    }

    @Override
    public int deleteNoteViewById(Long id) {
        return noteViewMapper.deleteNoteViewById(id);
    }

    /**
     * 根据数据表id删除视图信息
     * 
     * @param dwtableId 数据表主键
     * @return 结果
     */
    public int deleteNoteViewByDwtableId(Long dwtableId)
    {

        //删除视图操作是级联删除，删除对象包括数据表下的记录record，表格数据item
        //删除item
        noteDwtableItemMapper.deleteNoteDwtableItemByDwtId(dwtableId);
        //删除record
        NoteView noteView = new NoteView();
        noteView.setDwtableId(dwtableId);
        List<NoteView> noteViewList = noteViewMapper.selectNoteViewList(noteView);
        for (NoteView view:noteViewList) {
            noteRecordMapper.deleteNoteRecordByViewId(view.getId());
        }
        //删除view
        return noteViewMapper.deleteNoteViewByDwtableId(dwtableId);
    }
}
