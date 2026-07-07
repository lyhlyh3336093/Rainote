package com.ruoyi.system.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.Map;


import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.utils.myHashMap;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.vo.NoteColumnVo;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.service.INoteColumnService;
import com.ruoyi.system.service.INoteRecordService;

/**
 * 列信息Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-04-03
 */
@Service
public class NoteColumnServiceImpl implements INoteColumnService 
{
    @Autowired
    private NoteColumnMapper noteColumnMapper;

    @Autowired
    private NoteDwtableMapper noteDwtableMapper;

    @Autowired
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Autowired
    private NoteRecordMapper noteRecordMapper;

    @Autowired
    private INoteRecordService noteRecordService;

    /**
     * 查询列信息
     * 
     * @param id 列信息主键
     * @return 列信息
     */
    @Override
    public NoteColumn selectNoteColumnById(Long id)
    {
        return noteColumnMapper.selectNoteColumnById(id);
    }

    /**
     * 查询列信息列表
     * 
     * @param noteColumn 列信息
     * @return 列信息
     */
    @Override
    public List<NoteColumn> selectNoteColumnList(NoteColumn noteColumn)
    {
        return noteColumnMapper.selectNoteColumnList(noteColumn);
    }

    @Override
    public List<NoteColumn> selectNoteDoubleLinkColumnList(Long dwtableId) {

        return noteColumnMapper.selectNoteDoubleLinkColumnList(dwtableId);
    }

    /**
     * 新增列信息
     * 
     * @param noteColumnvo 列信息
     * @return 结果
     */
    @Override
    public int insertNoteColumn(NoteColumnVo noteColumnvo)
    {

        NoteColumn noteColumn = new NoteColumn();

        if(noteColumnvo.getIsShow()==null){
            noteColumn.setIsShow(0L);
        }
        if(noteColumnvo.getProperty()!=null&&!"".equals(noteColumnvo.getProperty())){
            noteColumn.setProperty(noteColumnvo.getProperty().toString());
        }
        noteColumn.setName(noteColumnvo.getName());
        noteColumn.setType(noteColumnvo.getType());
        noteColumn.setDwtableId(noteColumnvo.getDwtableId());

        noteColumn.setSort(getNextSort(noteColumnvo.getDwtableId()));

        noteColumnMapper.insertNoteColumn(noteColumn);


        //新增列的时候要检查是否已经有记录了，如果有则新增这一列的item，value为空
        NoteRecordVo noteRecord = new NoteRecordVo();
        noteRecord.setDwtableId(noteColumnvo.getDwtableId());
        List<NoteRecord> list = noteRecordMapper.selectNoteRecordList(noteRecord);
        if(list.size()>0){
            for(int i=0;i<list.size();i++){
                NoteDwtableItem item = new NoteDwtableItem();
                item.setRecordId(list.get(i).getId());
                item.setDwtId(noteColumnvo.getDwtableId());
                item.setColumnId(noteColumn.getId());
                item.setValue("");
                noteDwtableItemMapper.insertNoteDwtableItem(item);
            }
        }


        if(noteColumnvo.getType()==21L){
            //如果是双向链接还需要去被链接的数据表中添加新的列
            //目前新加的列还不知道id和name，id需要等添加之后返回得到，name默认为”双向关联“

            //查出被关联的数据表
            myHashMap<String, Object> property = noteColumnvo.getProperty();
            Long tableId = Long.parseLong(property.get("table_id").toString());
            NoteDwtable linkDwtable = noteDwtableMapper.selectNoteDwtableById(tableId);
            NoteDwtable thisDwtable = noteDwtableMapper.selectNoteDwtableById(noteColumnvo.getDwtableId());
            //在被关联的数据表下增加新的一列，
            myHashMap<String,Object> backProperty = new myHashMap<String,Object>();
            backProperty.put("multiple",true);
            backProperty.put("table_id",noteColumnvo.getDwtableId());
            backProperty.put("table_name",thisDwtable.getName());
            backProperty.put("back_field_id",noteColumn.getId());
            backProperty.put("back_field_name",noteColumn.getName());
            NoteColumn belinkColumn = new NoteColumn();
            belinkColumn.setDwtableId(tableId);
            belinkColumn.setIsShow(0L);
            belinkColumn.setName(noteColumn.getName()+"的双向链接");
            belinkColumn.setType(21L);
            belinkColumn.setProperty(backProperty.toString());
            belinkColumn.setSort(getNextSort(tableId));
            noteColumnMapper.insertNoteColumn(belinkColumn);
            //在新增被关联的列之后，再重新更新现在这个列对象的property属性

            property.put("multiple",true);
            property.put("table_id",belinkColumn.getDwtableId());
            property.put("table_name",linkDwtable.getName());
            property.put("back_field_id",belinkColumn.getId());
            property.put("back_field_name",belinkColumn.getName());
            noteColumn.setProperty(property.toString());
            noteColumnMapper.updateNoteColumn(noteColumn);

            //新增列的时候要检查是否已经有记录了，如果有则新增这一列的item，value为空
            NoteRecordVo linkRecord = new NoteRecordVo();
            linkRecord.setDwtableId(linkDwtable.getId());
            List<NoteRecord> linkList = noteRecordMapper.selectNoteRecordList(linkRecord);
            if(linkList.size()>0){
                for(int i=0;i<linkList.size();i++){
                    NoteDwtableItem item = new NoteDwtableItem();
                    item.setRecordId(linkList.get(i).getId());
                    item.setDwtId(linkDwtable.getId());
                    item.setColumnId(belinkColumn.getId());
                    item.setValue("");
                    noteDwtableItemMapper.insertNoteDwtableItem(item);
                }
            }


        }else if(noteColumnvo.getType()==25L){
            //笔记关联,要先在列下面根据记录的有无来创建新的空白item
            System.out.println("这次的配置信息是:"+noteColumnvo.getProperty().toString());
        }else if(noteColumnvo.getType()==26L){
            //lookup，在列新增这一部分不需要额外操作
            System.out.println("新增lookup列，配置内容是:"+noteColumnvo.getProperty().toString());
        }
        return 1;
    }

    /**
     * 修改列信息
     * 
     * @param noteColumnvo 列信息
     * @return 结果
     */
    @Override
    public int updateNoteColumn(NoteColumnVo noteColumnvo)
    {

        //先获取原列信息，判断是不是双向链接，如果是，在修改的时候需要将之前双向连接的关联内容取消关联
        NoteColumn originColumn = noteColumnMapper.selectNoteColumnById(noteColumnvo.getId());

        if(originColumn.getType()==21L){
            deleteDataWhenLink(originColumn);
        }


        NoteColumn noteColumn = new NoteColumn();

        if(noteColumnvo.getIsShow()!=null && !noteColumnvo.getIsShow().equals("")){
            noteColumn.setIsShow(noteColumnvo.getIsShow());
        }else{
            noteColumn.setIsShow(0L);
        }
        noteColumn.setId(noteColumnvo.getId());
        if(noteColumnvo.getProperty()!=null && !noteColumnvo.getProperty().equals("")){
            noteColumn.setProperty(noteColumnvo.getProperty().toString());
        }
        noteColumn.setName(noteColumnvo.getName());
        noteColumn.setType(noteColumnvo.getType());
        noteColumn.setDwtableId(noteColumnvo.getDwtableId());
        noteColumnMapper.updateNoteColumn(noteColumn);

        //----lookup列dedupe开关变化时触发全量重算----
        if (originColumn.getType() == 26L && noteColumnvo.getType() == 26L
                && originColumn.getProperty() != null && noteColumn.getProperty() != null)
        {
            try
            {
                JSONObject oldProp = JSONObject.parseObject(originColumn.getProperty());
                JSONObject newProp = JSONObject.parseObject(noteColumn.getProperty());
                if (oldProp != null && newProp != null)
                {
                    boolean oldDedupe = Boolean.TRUE.equals(oldProp.getBoolean("dedupe"));
                    boolean newDedupe = Boolean.TRUE.equals(newProp.getBoolean("dedupe"));
                    if (oldDedupe != newDedupe)
                    {
                        noteRecordService.recomputeLookupColumnValues(noteColumn);
                    }
                }
            }
            catch (Exception e)
            {
                // property非合法JSON，跳过重算
            }
        }

        //----这里往下都是对 双向关联所作的处理-----


        //如果是双向链接还需要去被链接的数据表中添加新的列
        if(noteColumnvo.getType()==21L){
            //如果是双向链接还需要去被链接的数据表中添加新的列
            //目前新加的列还不知道id和name，id需要等添加之后返回得到，name默认为”双向关联“
            //查出被关联的数据表
            myHashMap<String, Object> property = noteColumnvo.getProperty();
            if(property==null){
                //说明就是修改了名称和是否可见，此时可以不用管下面的
                noteColumn.setName(noteColumnvo.getName());
                noteColumn.setIsShow(noteColumnvo.getIsShow());
                noteColumnMapper.updateNoteColumn(noteColumn);
                return 1;
            }
            Long tableId = Long.parseLong(property.get("table_id").toString());
            NoteDwtable linkDwtable = noteDwtableMapper.selectNoteDwtableById(tableId);
            NoteDwtable thisDwtable = noteDwtableMapper.selectNoteDwtableById(noteColumnvo.getDwtableId());
            //在被关联的数据表下增加新的一列，
            myHashMap<String,Object> backProperty = new myHashMap<String,Object>();
            backProperty.put("multiple",true);
            backProperty.put("table_id",noteColumnvo.getDwtableId());
            backProperty.put("table_name",thisDwtable.getName());
            backProperty.put("back_field_id",noteColumn.getId());
            backProperty.put("back_field_name",noteColumn.getName());
            NoteColumn belinkColumn = new NoteColumn();
            belinkColumn.setDwtableId(tableId);
            belinkColumn.setIsShow(noteColumnvo.getIsShow());
            belinkColumn.setName(noteColumn.getName()+"的双向链接");
            belinkColumn.setType(21L);
            belinkColumn.setProperty(backProperty.toString());
            belinkColumn.setSort(getNextSort(tableId));
            noteColumnMapper.insertNoteColumn(belinkColumn);
            //在新增被关联的列之后，再重新更新现在这个列对象的property属性

            property.put("multiple",true);
            property.put("table_id",belinkColumn.getDwtableId());
            property.put("table_name",linkDwtable.getName());
            property.put("back_field_id",belinkColumn.getId());
            property.put("back_field_name",belinkColumn.getName());
            noteColumn.setProperty(property.toString());
            noteColumnMapper.updateNoteColumn(noteColumn);

            //更新的时候，关联的那一头的数据表可能已经有了一些记录了，所以要在这些记录里面加一些空的item
            NoteRecordVo linkRecord = new NoteRecordVo();
            linkRecord.setDwtableId(linkDwtable.getId());
            List<NoteRecord> linkList = noteRecordMapper.selectNoteRecordList(linkRecord);
            if(linkList.size()>0){
                for(int i=0;i<linkList.size();i++){
                    NoteDwtableItem item = new NoteDwtableItem();
                    item.setRecordId(linkList.get(i).getId());
                    item.setDwtId(linkDwtable.getId());
                    item.setColumnId(belinkColumn.getId());
                    item.setValue("");
                    noteDwtableItemMapper.insertNoteDwtableItem(item);
                }
            }
        }
        //----这里往上都是对 双向关联所作的处理-----

        return 1;
    }


    /**
     * 修改列排序
     *
     * @param sorts 排序信息
     * @return 结果
     */
    @Override
    public int updateSort(List<Map<String, Object>> sorts){

        int result = 1;
        //对sorts做处理
        for(Map<String, Object> data:sorts){
            if(data.get("id")!=null && !data.get("id").equals("")){
                Long columnId = Long.parseLong(data.get("id").toString());
                NoteColumn column = noteColumnMapper.selectNoteColumnById(columnId);
                column.setSort(Long.parseLong(data.get("sort").toString()));
                noteColumnMapper.updateNoteColumn(column);
                result = column.getId().intValue();
            }
        }

        return result;
    }


    /**
     * 批量删除列信息
     * 
     * @param ids 需要删除的列信息主键
     * @return 结果
     */
    @Override
    public int deleteNoteColumnByIds(String[] ids)
    {
        //在删除列信息之前我们要判断是否是双向链接的列或者是双向链接关联的列，如果是，需要去另一方进行删除
        List<NoteColumn> columnList = noteColumnMapper.selectNoteColumnByIds(ids);
        if(columnList.size()>0){
            for (NoteColumn noteColumn:columnList) {
                //删除列信息的时候，同时去检查是否有item信息，找到并删除
                noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(noteColumn.getId());
                if(noteColumn.getType()==21||noteColumn.getType()==25){
                    //先执行删除关联列和关联列下的item的操作
                    deleteDataWhenLink(noteColumn);
                }

            }
        }


        return noteColumnMapper.deleteNoteColumnByIds(ids);
    }

    /**
     * 删除列信息信息
     * 
     * @param id 列信息主键
     * @return 结果
     */
    @Override
    public int deleteNoteColumnById(Long id)
    {
        //删除列信息的时候，同时去检查是否有item信息，找到并删除
        noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(id);
        return noteColumnMapper.deleteNoteColumnById(id);
    }


    private boolean deleteDataWhenLink(NoteColumn originColumn){
        String property = originColumn.getProperty();
        //property为空说明不是双向链接列，无需处理
        if(property==null || property.isEmpty()){
            return false;
        }
        JSONObject jsonObject = JSONObject.parseObject(property);
        //删除被关联的数据表下的双向链接列
        if(jsonObject==null || jsonObject.isEmpty()||jsonObject.get("back_field_id")==null){
            return false;
        }
        Long linkColumnId =Long.parseLong(jsonObject.get("back_field_id").toString());
        noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(linkColumnId);
        noteColumnMapper.deleteNoteColumnById(linkColumnId);
        return true;
    }

    /**
     * 查询指定数据表中最大的sort值，返回下一个可用sort值(maxSort+1)
     *
     * @param dwtableId 数据表id
     * @return 下一个可用sort值
     */
    private Long getNextSort(Long dwtableId){
        NoteColumn queryColumn = new NoteColumn();
        queryColumn.setDwtableId(dwtableId);
        List<NoteColumn> existingColumns = noteColumnMapper.selectNoteColumnList(queryColumn);
        long maxSort = 0L;
        for (NoteColumn col : existingColumns) {
            if (col.getSort() != null && col.getSort() > maxSort) {
                maxSort = col.getSort();
            }
        }
        return maxSort + 1;
    }


}
