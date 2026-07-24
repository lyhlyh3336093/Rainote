package com.ruoyi.system.service.impl;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.utils.myHashMap;
import com.ruoyi.system.domain.NoteBlock;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteNotelink;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.vo.NoteColumnVo;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteBlockMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.service.INoteColumnService;
import com.ruoyi.system.service.INoteNotelinkService;
import com.ruoyi.system.service.INoteRecordService;
import com.ruoyi.system.service.NoteBlockContentService;

/**
 * 列信息Service业务层处理
 *
 * @author liuyanghe
 * @date 2023-04-03
 */
@Service
public class NoteColumnServiceImpl implements INoteColumnService
{
    private static final Logger log = LoggerFactory.getLogger(NoteColumnServiceImpl.class);

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

    @Autowired
    private INoteNotelinkService noteNotelinkService;

    @Autowired
    private NoteBlockContentService noteBlockContentService;

    @Autowired
    private NoteBlockMapper noteBlockMapper;

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
                        noteRecordService.recomputeSetOperationsForLookup(noteColumn);
                    }
                }
            }
            catch (Exception e)
            {
                // property非合法JSON或重算失败，跳过重算
                log.error("[UPDATE-COLUMN] dedupe重算失败 columnId={}", noteColumnvo.getId(), e);
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
                // 列结构变更触发按表重算 name（KTD-6 无条件重算，幂等）
                noteRecordService.recomputeRecordNamesForTable(noteColumnvo.getDwtableId());
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

        // 列结构变更触发按表重算 name（KTD-6 无条件重算，幂等）
        noteRecordService.recomputeRecordNamesForTable(noteColumnvo.getDwtableId());
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
        // 收集受影响的 dwtableId（去重），用于排序后按表重算 name
        java.util.Set<Long> affectedDwtableIds = new java.util.HashSet<>();
        //对sorts做处理
        for(Map<String, Object> data:sorts){
            if(data.get("id")!=null && !data.get("id").equals("")){
                Long columnId = Long.parseLong(data.get("id").toString());
                NoteColumn column = noteColumnMapper.selectNoteColumnById(columnId);
                column.setSort(Long.parseLong(data.get("sort").toString()));
                noteColumnMapper.updateNoteColumn(column);
                if (column.getDwtableId() != null) {
                    affectedDwtableIds.add(column.getDwtableId());
                }
                result = column.getId().intValue();
            }
        }
        // 重排后重算受影响表 name（KTD-6 无条件重算，幂等）
        for (Long dwtId : affectedDwtableIds) {
            noteRecordService.recomputeRecordNamesForTable(dwtId);
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
        // 收集受影响的 dwtableId（去重），用于删除后按表重算 name
        java.util.Set<Long> affectedDwtableIds = new java.util.HashSet<>();
        if(columnList.size()>0){
            for (NoteColumn noteColumn:columnList) {
                if(noteColumn.getDwtableId() != null){
                    affectedDwtableIds.add(noteColumn.getDwtableId());
                }
                if(noteColumn.getType()!=null && noteColumn.getType()==25){
                    // type=25 语义关联列:级联删除 + 文本恢复（R4-R10）
                    cascadeDeleteType25Column(noteColumn);
                } else {
                    // 其他类型:保持现有逻辑（先删 items,type=21 调 deleteDataWhenLink 清理配对列）
                    noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(noteColumn.getId());
                    if(noteColumn.getType()!=null && noteColumn.getType()==21){
                        deleteDataWhenLink(noteColumn);
                    }
                }
            }
        }

        int result = noteColumnMapper.deleteNoteColumnByIds(ids);
        // 删除后重算受影响表 name（KTD-6 无条件重算，幂等）
        for (Long dwtId : affectedDwtableIds) {
            noteRecordService.recomputeRecordNamesForTable(dwtId);
        }
        return result;
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
        // 删除前先取受影响 dwtableId，用于删除后按表重算 name
        NoteColumn column = noteColumnMapper.selectNoteColumnById(id);
        Long affectedDwtableId = column != null ? column.getDwtableId() : null;
        noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(id);
        int result = noteColumnMapper.deleteNoteColumnById(id);
        // 删除后重算受影响表 name（KTD-6 无条件重算，幂等）
        if (affectedDwtableId != null) {
            noteRecordService.recomputeRecordNamesForTable(affectedDwtableId);
        }
        return result;
    }

    /**
     * 切换指定lookup列(type=26)的去重开关，并重算该列存储值及关联的集合运算列。
     */
    @Override
    public int deduplicate(Long columnId)
    {
        NoteColumn column = noteColumnMapper.selectNoteColumnById(columnId);
        if (column == null || column.getType() != 26L || column.getProperty() == null)
        {
            return 0;
        }

        JSONObject prop;
        try
        {
            prop = JSONObject.parseObject(column.getProperty());
        }
        catch (Exception e)
        {
            // property非合法JSON，无法解析
            return 0;
        }
        if (prop == null)
        {
            return 0;
        }

        boolean currentDedupe = Boolean.TRUE.equals(prop.getBoolean("dedupe"));
        prop.put("dedupe", !currentDedupe);
        column.setProperty(prop.toJSONString());
        noteColumnMapper.updateNoteColumn(column);

        noteRecordService.recomputeLookupColumnValues(column);
        noteRecordService.recomputeSetOperationsForLookup(column);
        return 1;
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
        // 查询被关联列以获取其 dwtableId（删除前，KTD-6 无条件重算）
        NoteColumn linkColumn = noteColumnMapper.selectNoteColumnById(linkColumnId);
        // U2: 级联清理 NoteNotelink（反向启用后避免产生孤儿记录；service 内部已 try-catch）
        noteNotelinkService.deleteNoteNotelinkByColumnId(linkColumnId);
        noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(linkColumnId);
        noteColumnMapper.deleteNoteColumnById(linkColumnId);
        // 删除后重算被关联表 name（KTD-6 无条件重算，幂等）
        if (linkColumn != null && linkColumn.getDwtableId() != null) {
            noteRecordService.recomputeRecordNamesForTable(linkColumn.getDwtableId());
        }
        return true;
    }


    /**
     * type=25 语义关联列级联删除：R4-R10 完整流程。
     * <p>
     * 顺序约束（F1）：先 SELECT 收集 → 文本恢复 → DELETE records。
     * 列本身的删除由调用方 deleteNoteColumnByIds 统一执行。
     * <p>
     * 错误处理（R9/R10）：per-block try-catch，文本恢复失败不中断记录删除；
     * 列与记录始终删除（best-effort，与现有 deleteNoteNotelinkByColumnId 模式一致）。
     * <p>
     * 约束（来自 docs/solutions/architecture-patterns/lookup-column-dedupe-cascade-recompute.md）：
     * - NPE 守护：收集 tuple 时同时检查三者非空（修复4）
     * - per-block catch 用标识键 log.error，绝不静默吞（修复3）
     * - 严格按顺序：先 SELECT 收集，再 DELETE（itemMap-vs-DB 时序不变量）
     *
     * @param noteColumn 被删的 type=25 列
     */
    private void cascadeDeleteType25Column(NoteColumn noteColumn) {
        Long columnId = noteColumn.getId();

        // R6: 收集 FORWARD 方向的 NoteDwtableItem（linkBlockId IS NOT NULL，解耦条件）
        // 解耦 R6 收集条件：基于 linkBlockId 存在性而非 cell value 非空，
        // 修复 value-clear 路径下 linkBlockId 保留导致 F2 遗漏 FORWARD 文本恢复的问题
        List<NoteDwtableItem> forwardItems = noteDwtableItemMapper.selectItemsByColumnIdWithLinkBlockId(columnId);

        // R4 前置: 收集 REVERSE 方向的 NoteNotelink（按 linkColumnId）
        NoteNotelink queryNotelink = new NoteNotelink();
        queryNotelink.setLinkColumnId(columnId);
        List<NoteNotelink> reverseLinks = noteNotelinkService.selectNoteNotelinkList(queryNotelink);

        boolean hasForward = forwardItems != null && !forwardItems.isEmpty();
        boolean hasReverse = reverseLinks != null && !reverseLinks.isEmpty();

        // F2: 无 FORWARD 和 REVERSE 数据，只做防御性删除（R4/R5 在每次 type=25 删除时运行）
        if (!hasForward && !hasReverse) {
            noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(columnId);
            noteNotelinkService.deleteNoteNotelinkByColumnId(columnId);
            return;
        }

        // F1: 有数据路径，执行文本恢复（R6-R8）
        // 收集 REVERSE 的 linkId 集合（R7 REVERSE 匹配谓词：data-link-id ∈ reverseLinkIds）
        Set<Long> reverseLinkIds = new HashSet<>();
        if (hasReverse) {
            for (NoteNotelink link : reverseLinks) {
                if (link.getId() != null) {
                    reverseLinkIds.add(link.getId());
                }
            }
        }

        // 收集 FORWARD 的匹配键集合 "dwtId:recordId"（R7 FORWARD 历史锚点降级匹配）
        Set<String> forwardKeys = new HashSet<>();
        if (hasForward) {
            for (NoteDwtableItem item : forwardItems) {
                // NPE 守护：同时检查三者非空（learnings 修复4）
                if (item.getLinkBlockId() != null && item.getDwtId() != null && item.getRecordId() != null) {
                    forwardKeys.add(item.getDwtId() + ":" + item.getRecordId());
                }
            }
        }

        // 收集需要恢复的 NoteBlock ID 集合（REVERSE by blockId + FORWARD by linkBlockId）
        Set<Long> blockIdsToRestore = new HashSet<>();
        if (hasReverse) {
            for (NoteNotelink link : reverseLinks) {
                if (link.getBlockId() != null) {
                    blockIdsToRestore.add(link.getBlockId());
                }
            }
        }
        if (hasForward) {
            for (NoteDwtableItem item : forwardItems) {
                if (item.getLinkBlockId() != null) {
                    blockIdsToRestore.add(item.getLinkBlockId());
                }
            }
        }

        // R7/R8/R9: 文本恢复（per-block try-catch）
        for (Long blockId : blockIdsToRestore) {
            try {
                NoteBlock block = noteBlockMapper.selectNoteBlockById(blockId);
                if (block == null) {
                    // NoteBlock 已被独立删除，跳过（不 NPE，来自 learnings lookup-column-set-operation-logic-errors 修复4）
                    continue;
                }
                String propertyJson = block.getProperty();
                if (propertyJson == null || propertyJson.isEmpty()) {
                    continue;
                }
                String restoredProperty = noteBlockContentService.restoreAnchors(
                    propertyJson, columnId, reverseLinkIds, forwardKeys);
                if (restoredProperty != null && !restoredProperty.equals(propertyJson)) {
                    // 部分更新：只写 property 字段，避免覆盖并发修改的 parentId/childId/blockType/sort
                    NoteBlock patch = new NoteBlock();
                    patch.setId(block.getId());
                    patch.setProperty(restoredProperty);
                    noteBlockMapper.updateNoteBlock(patch);
                }
            } catch (Exception e) {
                // R9: per-block try-catch + log.error（标识键，来自 learnings 修复3）
                log.error("[CASCADE-DELETE] columnId={}, blockId={}, text restoration failed",
                    columnId, blockId, e);
                // 不中断后续块处理（来自 learnings 修复6，不贯穿空结果）
            }
        }

        // R5: 删除 FORWARD 的 NoteDwtableItem（先执行 fail-fast 操作，与 F2 路径顺序一致）
        noteDwtableItemMapper.deleteNoteDwtableItemByColumnId(columnId);

        // R4: 删除 REVERSE 的 NoteNotelink（传被删列自身 id，非 back_field_id）
        // 已有 deleteNoteNotelinkByColumnId 含 try-catch，符合 best-effort
        noteNotelinkService.deleteNoteNotelinkByColumnId(columnId);
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
