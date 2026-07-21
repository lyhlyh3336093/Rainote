package com.ruoyi.system.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.service.INoteDwtableService;

/**
 * 多维表格数据表Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
@Service
public class NoteDwtableServiceImpl implements INoteDwtableService 
{
    @Autowired
    private NoteDwtableMapper noteDwtableMapper;

    @Autowired
    private NoteColumnMapper noteColumnMapper;

    @Autowired
    private NoteViewMapper noteViewMapper;

    @Autowired
    private NoteViewServiceImpl noteViewServiceImpl;

    @Autowired
    private NoteRecordMapper noteRecordMapper;

    @Autowired
    private NoteDwtableItemMapper noteDwtableItemMapper;

    /**
     * 查询多维表格数据表
     * 
     * @param id 多维表格数据表主键
     * @return 多维表格数据表
     */
    @Override
    public NoteDwtable selectNoteDwtableById(Long id)
    {
        return noteDwtableMapper.selectNoteDwtableById(id);
    }


    /**
     * 查询多维表格数据表表内数据
     *
     * @param id 多维表格数据表主键
     * @return 多维表格数据表
     */
    @Override
    public Map<String,Object> selectNoteDwtableDataById(Long id)
    {
        Map<String,Object> result = new HashMap<String,Object>();
        NoteColumn noteColumn = new NoteColumn();
        noteColumn.setDwtableId(id);
        //先查出来这个数据表下有多少字段
        List<NoteColumn> noteColumnList =  noteColumnMapper.selectNoteColumnList(noteColumn);
        //查出所有数据
        NoteDwtableItem noteDwtableItem = new NoteDwtableItem();
        noteDwtableItem.setDwtId(id);
        List<NoteDwtableItem> dwtableItemList =  noteDwtableItemMapper.selectNoteDwtableItemList(noteDwtableItem);
        Map<String,Object> itemMap = new HashMap<String,Object>();
        List<Map<String,Object>> recordMapList = new ArrayList<Map<String,Object>>();
        //把数据按照记录id分组，每组是一行的，
        for(int i=0;i<dwtableItemList.size();i++){
            if(itemMap.containsKey(dwtableItemList.get(i).getRecordId())){

            }else{
                Map<String,Object> recordMap = new HashMap<String,Object>();

            }
        }


        //把详细数据和列信息对应并组合成map


        NoteDwtable dwtable= noteDwtableMapper.selectNoteDwtableById(id);



        return result;
    }

    /**
     * 查询多维表格数据表列表
     * 
     * @param noteDwtable 多维表格数据表
     * @return 多维表格数据表
     */
    @Override
    public List<NoteDwtable> selectNoteDwtableList(NoteDwtable noteDwtable)
    {
        return noteDwtableMapper.selectNoteDwtableList(noteDwtable);
    }

    /**
     * 新增多维表格数据表
     * 
     * @param noteDwtable 多维表格数据表
     * @return 结果
     */
    @Override
    public int insertNoteDwtable(NoteDwtable noteDwtable)
    {
        if(noteDwtable.getDelFlag()==null){
            noteDwtable.setDelFlag(0L);
        }
        noteDwtableMapper.insertNoteDwtable(noteDwtable);
        int id = noteDwtable.getId().intValue();

        return id;
    }

    /**
     * 修改多维表格数据表
     * 
     * @param noteDwtable 多维表格数据表
     * @return 结果
     */
    @Override
    public int updateNoteDwtable(NoteDwtable noteDwtable)
    {
        return noteDwtableMapper.updateNoteDwtable(noteDwtable);
    }

    /**
     * 批量删除多维表格数据表
     * 
     * @param ids 需要删除的多维表格数据表主键
     * @return 结果
     */
    @Override
    public int deleteNoteDwtableByIds(String[] ids)
    {
        return noteDwtableMapper.deleteNoteDwtableByIds(ids);
    }

    /**
     * 删除多维表格数据表信息
     * 
     * @param id 多维表格数据表主键
     * @return 结果
     */
    @Override
    public int deleteNoteDwtableById(Long id)
    {
        //删除数据表操作是级联删除，删除对象包括数据表下的视图view，记录record，表格数据item
        noteViewServiceImpl.deleteNoteViewByDwtableId(id);
        //删除record
        noteRecordMapper.deleteNoteRecordByDwtableId(id);
        //删除column,要先查询出数据表下的列里面有没有双向链接类型的列,如果有,则不能直接删除
        //先把双向链接类型的关联列和关联列下的item删掉再删除列
        NoteColumn noteColumn = new NoteColumn();
        noteColumn.setDwtableId(id);
        List<NoteColumn> noteColumnList =  noteColumnMapper.selectNoteColumnList(noteColumn);
        for(int i=0;i<noteColumnList.size();i++){
            if(noteColumnList.get(i).getType()==21){
                //双向链接类型的列,需要删除关联的item
                JSONObject property = JSONObject.parseObject(noteColumnList.get(i).getProperty());
                //从property中获取关联列的id
                Long linkColumnId = property.getLong("back_field_id");
                //删除关联列下的所有item
                noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(linkColumnId);
                //删除关联列
                noteColumnMapper.deleteNoteColumnById(linkColumnId);
                //删除当前列下的所有item
                noteDwtableItemMapper.deleteNoteDwtableItemByDwtId(noteColumnList.get(i).getId());
            }
        }
        return noteDwtableMapper.deleteNoteDwtableById(id);
    }
}
