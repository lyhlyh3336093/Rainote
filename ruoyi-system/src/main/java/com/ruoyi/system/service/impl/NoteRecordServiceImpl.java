package com.ruoyi.system.service.impl;

import java.util.*;
import java.util.stream.Collectors;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.*;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.system.service.INoteRecordService;

/**
 * 记录Service业务层处理
 * 
 * @author liuyanghe
 * @date 2023-04-12
 */
@Service
public class NoteRecordServiceImpl implements INoteRecordService
{
    private static final Logger log = LoggerFactory.getLogger(NoteRecordServiceImpl.class);

    @Autowired
    private NoteRecordMapper noteRecordMapper;

    @Autowired
    private NoteColumnMapper noteColumnMapper;

    @Autowired
    private NoteViewMapper noteViewMapper;

    @Autowired
    private NoteDwtableItemMapper noteDwtableItemMapper;



    @Autowired
    private NoteDwtableMapper noteDwtableMapper;

    /**
     * 查询记录
     * 
     * @param id 记录主键
     * @return 记录
     */
    @Override
    public NoteRecord selectNoteRecordById(Long id)
    {
        return noteRecordMapper.selectNoteRecordById(id);
    }

    /**
     * 查询记录列表
     * 
     * @param noteRecord 记录
     * @return 记录
     */
    @Override
    public List<NoteRecord> selectNoteRecordList(NoteRecordVo noteRecord)
    {
        return noteRecordMapper.selectNoteRecordList(noteRecord);
    }

    /**
     * 新增记录
     * 
     * @param noteRecord 记录
     * @return 结果
     */
    @Override
    @Transactional
    public int insertNoteRecord(NoteRecord noteRecord,List<Map<String, Object>> items)
    {
        if(noteRecord.getDwtableId()==null){
            NoteView noteView = noteViewMapper.selectNoteViewById(noteRecord.getViewId());
            NoteDwtable noteDwtable = noteDwtableMapper.selectNoteDwtableById(noteView.getDwtableId());
            noteRecord.setDwtableId(noteDwtable.getId());
        }
        noteRecordMapper.insertNoteRecord(noteRecord);
        if(items!=null&&items.size()>0&&items.get(0).get("dwtId")!=null){
            //拆出来里面的items，去做对应的保存
            for (Map<String, Object> itemMap:
                    items) {
                NoteDwtableItem item = new NoteDwtableItem();
                item.setRecordId(noteRecord.getId());
                item.setDwtId(Long.parseLong(itemMap.get("dwtId").toString()));
                item.setColumnId(Long.parseLong(itemMap.get("columnId").toString()));
                Object valueObj = itemMap.get("value");
                item.setValue(valueObj == null ? "" : valueObj.toString());
                if(itemMap.get("recordId")!=null){
                    item.setLinkRecordId(itemMap.get("recordId").toString());
                }

                noteDwtableItemMapper.insertNoteDwtableItem(item);
            }
            // 派生 name（R1/R3，KTD-2 insert 路径 dwtableId 已由 service 从 view 解析）
            String derivedName = deriveRecordName(noteRecord.getDwtableId(), items, null);
            noteRecord.setName(derivedName);
            noteRecordMapper.updateNoteRecord(noteRecord);
            return noteRecord.getId().intValue();
        }else{
            // 派生 name（R7：无 items 时仍派生，无 type=1 列则 name=""）
            String derivedName = deriveRecordName(noteRecord.getDwtableId(), null, null);
            noteRecord.setName(derivedName);
            noteRecordMapper.updateNoteRecord(noteRecord);
            return 0;
        }

    }

    /**
     * 修改记录
     * 
     * @param noteRecord 记录
     * @return 结果
     */
    @Override
    @Transactional
    public int updateNoteRecord(NoteRecord noteRecord,List<Map<String, Object>> items) {
        NoteDwtableItem queryParam = new NoteDwtableItem();
        queryParam.setRecordId(noteRecord.getId());
        List<NoteDwtableItem> recordItems = noteDwtableItemMapper.selectNoteDwtableItemList(queryParam);
        // 派生 name（R1/R2/R7，KTD-2 从 recordItems 反查 dwtableId；KTD-3 先 incoming items 再 recordItems）
        // 始终派生，前端传入的 name 被覆盖；recordItems 为空则跳过派生、保持原 name（edge）
        if (!recordItems.isEmpty()) {
            Long dwtableId = recordItems.get(0).getDwtId();
            String derivedName = deriveRecordName(dwtableId, items, recordItems);
            noteRecord.setName(derivedName);
        }
        noteRecordMapper.updateNoteRecord(noteRecord);
        if (items != null) {
            //拆出来里面的items，去做对应的保存
            for (Map<String, Object> itemMap :
                    items) {
                NoteDwtableItem item = new NoteDwtableItem();
                if (itemMap.get("id") != null) {
                    item.setId(Long.parseLong(itemMap.get("id").toString()));
                    if (itemMap.get("value") == null || itemMap.get("value").equals("")) {
                        item.setValue("");
                    } else {
                        item.setValue(itemMap.get("value").toString());
                    }
                    //以下是几种特殊处理的列类型（双向链接、数学公式、集合运算）

                    String columnId = itemMap.get("columnId").toString();
                    NoteColumn queryColumn = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnId));
                    NoteColumn allColumn = new NoteColumn();
                    allColumn.setDwtableId(noteRecord.getDwtableId());
                    List<NoteColumn> allColumnList = noteColumnMapper.selectNoteColumnList(allColumn);

                    String linkColumnId;
                    //判断当前数据表中是否有数学公式列以及修改的item所属的列是否已经被某个数学公式列给关联了
                    List<NoteColumn> columnList = noteColumnMapper.selectCalcColumnByDwtId(queryColumn.getDwtableId());
                    if (columnList.size() > 0) {
                        //存在数学公式列，判断本列是否被关联
                        for (NoteColumn column : columnList) {
                            //数学公式
                            JSONObject jsonObject = JSONObject.parseObject(column.getProperty());
                            String expression = jsonObject.get("expression").toString();
                            ExpressionParser parser = new SpelExpressionParser();
                            String columnAId = jsonObject.get("columnAId").toString();
                            String columnBId = jsonObject.get("columnBId").toString();
                            if (columnAId.equals(columnId)) {
                                //该item是参与运算的A列

                                NoteDwtableItem queryItemB = new NoteDwtableItem();
                                queryItemB.setRecordId(noteRecord.getId());
                                queryItemB.setColumnId(Long.parseLong(columnBId));
                                NoteDwtableItem itemB = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItemB);
                                if (itemB.getValue() == null || "".equals(itemB.getValue())) {
                                    continue;
//                                    itemB.setValue("0");
                                }
                                String ex = expression.replace("A", itemMap.get("value").toString()).replace("B", itemB.getValue().toString());
                                //算术运算
                                Float result = parser.parseExpression(ex).getValue(Float.class);
                                //更新数学公式列的结果
                                NoteDwtableItem queryItemResult = new NoteDwtableItem();
                                queryItemResult.setRecordId(noteRecord.getId());
                                queryItemResult.setColumnId(column.getId());
                                NoteDwtableItem itemResult = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItemResult);

                                itemResult.setValue(result.toString());
                                noteDwtableItemMapper.updateNoteDwtableItem(itemResult);


                            } else if (columnBId.equals(columnId)) {
                                //该item是参与运算的B列

                                NoteDwtableItem queryItemA = new NoteDwtableItem();
                                queryItemA.setRecordId(noteRecord.getId());
                                queryItemA.setColumnId(Long.parseLong(columnAId));
                                NoteDwtableItem itemA = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItemA);
                                if (itemA.getValue() == null || "".equals(itemA.getValue())) {
                                    continue;
//                                    itemA.setValue("0");
                                }

                                String ex = expression.replace("B", itemMap.get("value").toString()).replace("A", itemA.getValue().toString());
                                //算术运算
                                Float result = parser.parseExpression(ex).getValue(Float.class);
                                //更新数学公式列的结果
                                NoteDwtableItem queryItemResult = new NoteDwtableItem();
                                queryItemResult.setRecordId(noteRecord.getId());
                                queryItemResult.setColumnId(column.getId());
                                NoteDwtableItem itemResult = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItemResult);

                                itemResult.setValue(result.toString());
                                noteDwtableItemMapper.updateNoteDwtableItem(itemResult);
                            }
                        }
                    }

                    //集合运算开始
                    //判断当前数据表中是否有集合运算列以及修改的item所属的列是否已经被某个集合运算列给关联了
                    List<NoteColumn> setColumnList = noteColumnMapper.selectSetColumnByDwtId(queryColumn.getDwtableId());

                    if (setColumnList.size() > 0) {

                        for (NoteColumn column : setColumnList) {
                            JSONObject jsonObject = JSONObject.parseObject(column.getProperty());
                            String columnAId = jsonObject.get("columnAId").toString();
                            String columnBId = jsonObject.get("columnBId").toString();
                            String calcType = jsonObject.get("calcType").toString();
                            List<String> aRecordIds = null;
                            List<String> bRecordIds = null;
                            List<String> aValues = null;
                            List<String> bValues = null;

                            //判断当前修改的列是否触发了此集合运算
                            //1. 直接匹配：修改的列就是A列或B列
                            //2. 间接匹配：修改的列是lookup列的double_link_column_id，或其配对双向链接列
                            boolean aMatched = columnAId.equals(columnId);
                            boolean bMatched = columnBId.equals(columnId);
                            // 获取当前修改列的back_field_id，用于间接匹配
                            String currentBackFieldId = "";
                            if (!aMatched || !bMatched) {
                                NoteColumn currentCol = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnId));
                                if (currentCol != null && currentCol.getType() == 21L) {
                                    JSONObject currentProp = JSONObject.parseObject(currentCol.getProperty());
                                    currentBackFieldId = currentProp.get("back_field_id") != null ? currentProp.get("back_field_id").toString() : "";
                                }
                            }
                            if (!aMatched) {
                                NoteColumn colA = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnAId));
                                if (colA != null && colA.getType() == 26L) {
                                    JSONObject propA = JSONObject.parseObject(colA.getProperty());
                                    String doubleLinkColIdA = propA.get("double_link_column_id") != null ? propA.get("double_link_column_id").toString() : "";
                                    if (columnId.equals(doubleLinkColIdA) || currentBackFieldId.equals(doubleLinkColIdA)) {
                                        aMatched = true;
                                    }
                                }
                            }
                            if (!bMatched) {
                                NoteColumn colB = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnBId));
                                if (colB != null && colB.getType() == 26L) {
                                    JSONObject propB = JSONObject.parseObject(colB.getProperty());
                                    String doubleLinkColIdB = propB.get("double_link_column_id") != null ? propB.get("double_link_column_id").toString() : "";
                                    if (columnId.equals(doubleLinkColIdB) || currentBackFieldId.equals(doubleLinkColIdB)) {
                                        bMatched = true;
                                    }
                                }
                            }

                            if (aMatched) {
                                // A列数据获取
                                NoteColumn columnA = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnAId));
                                if (columnA != null && columnA.getType() == 26L) {
                                    List<String> aLinkIds = getLookupLinkRecordIds(columnA, columnId, currentBackFieldId, itemMap, noteRecord.getId());
                                    if (aLinkIds == null) {
                                        continue;
                                    }
                                    List<LookupResult> aResults = resolveLookupValues(columnA, aLinkIds);
                                    aRecordIds = aResults.stream().map(LookupResult::getLinkRecordId).collect(Collectors.toList());
                                    aValues = toLookupValues(aResults);
                                    log.info("[SET-OP-LOOKUP-A] aRecordIds={}, aValues={}", aRecordIds, aValues);
                                } else {
                                    // type=21 双向关联列
                                    if (columnAId.equals(columnId)) {
                                        // 直接匹配：itemMap就是A列的数据
                                        aRecordIds = new ArrayList<String>(Arrays.asList(itemMap.get("recordId").toString().split(",")));
                                        aValues = new ArrayList<String>(Arrays.asList(itemMap.get("value").toString().split(",")));
                                    } else {
                                        // 间接匹配：从数据库查询A列数据
                                        NoteDwtableItem queryItemA = new NoteDwtableItem();
                                        queryItemA.setRecordId(noteRecord.getId());
                                        queryItemA.setColumnId(Long.parseLong(columnAId));
                                        NoteDwtableItem itemA = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItemA);
                                        if (itemA == null || itemA.getLinkRecordId() == null || "".equals(itemA.getLinkRecordId())) {
                                            continue;
                                        }
                                        aRecordIds = new ArrayList<String>(Arrays.asList(itemA.getLinkRecordId().split(",")));
                                        aValues = new ArrayList<String>(Arrays.asList(itemA.getValue().split(",")));
                                    }
                                }

                                // B列数据获取
                                NoteColumn columnB = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnBId));
                                if (columnB != null && columnB.getType() == 26L) {
                                    List<String> bLinkIds = getLookupLinkRecordIds(columnB, columnId, currentBackFieldId, itemMap, noteRecord.getId());
                                    if (bLinkIds == null) {
                                        continue;
                                    }
                                    List<LookupResult> bResults = resolveLookupValues(columnB, bLinkIds);
                                    bRecordIds = bResults.stream().map(LookupResult::getLinkRecordId).collect(Collectors.toList());
                                    bValues = toLookupValues(bResults);
                                    log.info("[SET-OP-LOOKUP-B] bRecordIds={}, bValues={}", bRecordIds, bValues);
                                } else {
                                    // type=21 双向关联列：直接从item取linkRecordId和value
                                    NoteDwtableItem queryItemB = new NoteDwtableItem();
                                    queryItemB.setRecordId(noteRecord.getId());
                                    queryItemB.setColumnId(Long.parseLong(columnBId));
                                    NoteDwtableItem itemB = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItemB);
                                    if (itemB == null || itemB.getLinkRecordId() == null || "".equals(itemB.getLinkRecordId())) {
                                        continue;
                                    }
                                    bRecordIds = new ArrayList<String>(Arrays.asList(itemB.getLinkRecordId().split(",")));
                                    bValues = new ArrayList<String>(Arrays.asList(itemB.getValue().split(",")));
                                }

                            } else if (bMatched) {
                                // A列数据获取
                                NoteColumn columnA = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnAId));
                                if (columnA != null && columnA.getType() == 26L) {
                                    List<String> aLinkIds = getLookupLinkRecordIds(columnA, columnId, currentBackFieldId, itemMap, noteRecord.getId());
                                    if (aLinkIds == null) {
                                        continue;
                                    }
                                    List<LookupResult> aResults = resolveLookupValues(columnA, aLinkIds);
                                    aRecordIds = aResults.stream().map(LookupResult::getLinkRecordId).collect(Collectors.toList());
                                    aValues = toLookupValues(aResults);
                                    log.info("[SET-OP-LOOKUP-A] aRecordIds={}, aValues={}", aRecordIds, aValues);
                                } else {
                                    // type=21 双向关联列：直接从item取linkRecordId和value
                                    NoteDwtableItem queryItemA = new NoteDwtableItem();
                                    queryItemA.setRecordId(noteRecord.getId());
                                    queryItemA.setColumnId(Long.parseLong(columnAId));
                                    NoteDwtableItem itemA = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItemA);
                                    if (itemA == null || itemA.getLinkRecordId() == null || "".equals(itemA.getLinkRecordId())) {
                                        continue;
                                    }
                                    aRecordIds = new ArrayList<String>(Arrays.asList(itemA.getLinkRecordId().split(",")));
                                    aValues = new ArrayList<String>(Arrays.asList(itemA.getValue().split(",")));
                                }

                                // B列数据获取
                                NoteColumn columnB = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnBId));
                                if (columnB != null && columnB.getType() == 26L) {
                                    List<String> bLinkIds = getLookupLinkRecordIds(columnB, columnId, currentBackFieldId, itemMap, noteRecord.getId());
                                    if (bLinkIds == null) {
                                        continue;
                                    }
                                    List<LookupResult> bResults = resolveLookupValues(columnB, bLinkIds);
                                    bRecordIds = bResults.stream().map(LookupResult::getLinkRecordId).collect(Collectors.toList());
                                    bValues = toLookupValues(bResults);
                                    log.info("[SET-OP-LOOKUP-B] bRecordIds={}, bValues={}", bRecordIds, bValues);
                                } else {
                                    // type=21 双向关联列
                                    if (columnBId.equals(columnId)) {
                                        // 直接匹配：itemMap就是B列的数据
                                        bRecordIds = new ArrayList<String>(Arrays.asList(itemMap.get("recordId").toString().split(",")));
                                        bValues = new ArrayList<String>(Arrays.asList(itemMap.get("value").toString().split(",")));
                                    } else {
                                        // 间接匹配：从数据库查询B列数据
                                        NoteDwtableItem queryItemB = new NoteDwtableItem();
                                        queryItemB.setRecordId(noteRecord.getId());
                                        queryItemB.setColumnId(Long.parseLong(columnBId));
                                        NoteDwtableItem itemB = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItemB);
                                        if (itemB == null || itemB.getLinkRecordId() == null || "".equals(itemB.getLinkRecordId())) {
                                            continue;
                                        }
                                        bRecordIds = new ArrayList<String>(Arrays.asList(itemB.getLinkRecordId().split(",")));
                                        bValues = new ArrayList<String>(Arrays.asList(itemB.getValue().split(",")));
                                    }
                                }
                            } else {
                                continue;
                            }

                            log.info("[SET-OP] columnId={}, recordId={}, aMatched={}, bMatched={}, columnAId={}, columnBId={}, calcType={}", column.getId(), noteRecord.getId(), aMatched, bMatched, columnAId, columnBId, calcType);
                            log.info("[SET-OP] aRecordIds={}, aValues={}", aRecordIds, aValues);
                            log.info("[SET-OP] bRecordIds={}, bValues={}", bRecordIds, bValues);

                            //构建recordId→value映射，用于集合运算后反查value
                            Map<String, String> aMap = new LinkedHashMap<>();
                            for (int i = 0; i < aRecordIds.size(); i++) {
                                aMap.put(aRecordIds.get(i), i < aValues.size() ? aValues.get(i) : "");
                            }
                            Map<String, String> bMap = new LinkedHashMap<>();
                            for (int i = 0; i < bRecordIds.size(); i++) {
                                bMap.put(bRecordIds.get(i), i < bValues.size() ? bValues.get(i) : "");
                            }

                            //结果
                            List<String> rRecordIds = new ArrayList<String>();
                            List<String> rValues = new ArrayList<String>();

                            if (calcType.equals("disjunction")) {
                                //补集：基于linkRecordId运算
                                rRecordIds = (List<String>) CollectionUtils.disjunction(aRecordIds, bRecordIds);
                            } else if (calcType.equals("subtract")) {
                                //差集：基于linkRecordId运算
                                rRecordIds = (List<String>) CollectionUtils.subtract(aRecordIds, bRecordIds);
                            } else if (calcType.equals("intersection")) {
                                //交集：基于linkRecordId运算
                                rRecordIds = (List<String>) CollectionUtils.intersection(aRecordIds, bRecordIds);
                            } else if (calcType.equals("union")) {
                                //并集：基于linkRecordId运算
                                rRecordIds = (List<String>) CollectionUtils.union(aRecordIds, bRecordIds);
                            } else {
                                //待扩展
                            }

                            log.info("[SET-OP] rRecordIds={}, rValues={}", rRecordIds, rValues);

                            //根据运算后的rRecordIds反查value，保证value和linkRecordId一一对应
                            for (String id : rRecordIds) {
                                String value = aMap.get(id);
                                if (value == null) {
                                    value = bMap.get(id);
                                }
                                if (value == null) {
                                    value = "";
                                }
                                rValues.add(value);
                            }
                            //更新集合运算列的item内容
                            NoteDwtableItem queryItem = new NoteDwtableItem();
                            queryItem.setRecordId(noteRecord.getId());
                            queryItem.setColumnId(column.getId());
                            NoteDwtableItem resultItem = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItem);
                            if (resultItem == null) {
                                resultItem = new NoteDwtableItem();
                                resultItem.setRecordId(noteRecord.getId());
                                resultItem.setColumnId(column.getId());
                                resultItem.setDwtId(queryColumn.getDwtableId());
                            }
                            resultItem.setValue(rValues.toString().replace("[", "").replace("]", "").replaceAll(" ", ""));
                            resultItem.setLinkRecordId(rRecordIds.toString().replace("[", "").replace("]", "").replaceAll(" ", ""));
                            if (resultItem.getId() == null) {
                                noteDwtableItemMapper.insertNoteDwtableItem(resultItem);
                            } else {
                                noteDwtableItemMapper.updateNoteDwtableItem(resultItem);
                            }
                        }
                    }
                    //集合运算结束


                    //每一个item对应的column都是存在且有其列类型的，根据列类型来进行情况分类
                    if (queryColumn.getType() == 21L) {
                        if (itemMap.get("recordId") != null) {
                            //recordid不为空说明是双向关联
                            if (itemMap.get("oldRecordId") == null || itemMap.get("oldRecordId").toString().equals("")) {
                                //新增
                                String[] ids = itemMap.get("recordId").toString().split(",");


                                if (itemMap.get("linkColumnId") != null && !itemMap.get("linkColumnId").toString().equals("")) {
                                    linkColumnId = itemMap.get("linkColumnId").toString();
                                } else {

                                    JSONObject jsonObject = JSONObject.parseObject(queryColumn.getProperty());
                                    linkColumnId = jsonObject.get("back_field_id").toString();
                                }

                                List<NoteRecord> noteRecords = noteRecordMapper.selectNoteRecordByIds(ids);
                                for (int i = 0; i < noteRecords.size(); i++) {
                                    NoteDwtableItem queryItem = new NoteDwtableItem();
                                    queryItem.setRecordId(noteRecords.get(i).getId());
                                    queryItem.setColumnId(Long.parseLong(linkColumnId));
                                    NoteDwtableItem linkItem = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItem);
                                    if (linkItem == null) {
                                        System.out.println("++++++++++++没找到绑定的另一头的item+++++++++++");
                                    }

                                    //将被关联的item进行更新

                                    linkItem.setLinkColumnId(Long.parseLong(columnId));
                                    //检查关联的item里面的linkRecordid里有没有这一条，没有就加上
                                    if (linkItem.getRecordId() != null && !linkItem.getRecordId().equals("")) {
                                        if (linkItem.getLinkRecordId() != null && !linkItem.getLinkRecordId().equals("")) {
                                            String[] linkRecordIds = linkItem.getLinkRecordId().split(",");
                                            if (!ArrayUtils.contains(linkRecordIds, noteRecord.getId())) {
                                                linkItem.setLinkRecordId(linkItem.getLinkRecordId() + "," + noteRecord.getId());
                                            }
                                        } else {
                                            linkItem.setLinkRecordId(noteRecord.getId().toString());
                                        }

                                    } else {
                                        linkItem.setLinkRecordId(noteRecord.getId().toString());
                                    }
                                    //检查关联的item里面的linkItemid里有没有这一条，没有就加上
                                    if (linkItem.getLinkItemId() != null && !linkItem.getLinkItemId().equals("")) {
                                        String[] linkItemIds = linkItem.getLinkItemId().split(",");
                                        if (!ArrayUtils.contains(linkItemIds, item.getId())) {
                                            linkItem.setLinkItemId(linkItem.getLinkItemId() + "," + item.getId());
                                        }
                                    } else {
                                        linkItem.setLinkItemId(item.getId().toString());
                                    }

                                    //检查关联的item里面的value里有没有这一条，没有就加上
                                    if (linkItem.getValue() != null && !linkItem.getValue().equals("")) {
                                        String[] values = linkItem.getValue().split(",");
                                        if (!ArrayUtils.contains(values, item.getValue())) {
                                            linkItem.setValue(linkItem.getValue() + "," + noteRecord.getName());
                                        }
                                    } else {
                                        linkItem.setValue(noteRecord.getName());
                                    }
                                    noteDwtableItemMapper.updateNoteDwtableItem(linkItem);
                                    //更新完被关联item之后，回过头来更新源数据的item
                                    if (item.getLinkItemId() == null || item.getLinkItemId().equals("")) {
                                        item.setLinkItemId(linkItem.getId().toString());
                                    } else {
                                        item.setLinkItemId(item.getLinkItemId() + "," + linkItem.getId().toString());
                                    }

                                    item.setLinkColumnId(linkItem.getColumnId());
                                    if (item.getLinkRecordId() == null || item.getLinkRecordId().equals("")) {
                                        item.setLinkRecordId(linkItem.getRecordId().toString());
                                    } else {
                                        item.setLinkRecordId(item.getLinkRecordId() + "," + linkItem.getRecordId().toString());
                                    }
                                }
                                //新增
                            } else {
                                //修改
                                String[] oldIds = itemMap.get("oldRecordId").toString().split(",");
                                String[] updateIds = itemMap.get("recordId").toString().split(",");
                                columnId = itemMap.get("columnId").toString();
                                if (itemMap.get("linkColumnId") != null && !itemMap.get("linkColumnId").toString().equals("")) {
                                    linkColumnId = itemMap.get("linkColumnId").toString();
                                } else {
                                    queryColumn = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnId));
                                    JSONObject jsonObject = JSONObject.parseObject(queryColumn.getProperty());
                                    linkColumnId = jsonObject.get("back_field_id").toString();
                                }
                                //根据双向关联的列ID和记录id，查出对应的item信息，将其中的linkRecordid、linkItemId进行更新
                                //先将所有oldRecordid的信息都从item的value以及linkRecordid里面进行删除，再将所有update的recordid的信息都录入
                                if (oldIds != null && oldIds.length > 0 && !oldIds[0].equals("[]")) {
                                    for (int i = 0; i < oldIds.length; i++) {
                                        NoteDwtableItem queryItem = new NoteDwtableItem();
                                        queryItem.setRecordId(Long.parseLong(oldIds[i]));
                                        queryItem.setColumnId(Long.parseLong(linkColumnId));
                                        //老表2
                                        NoteDwtableItem linkItem = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItem);
                                        //对被绑定的oldItem信息进行更新(如果有关联的话)
                                        if (linkItem != null && linkItem.getLinkRecordId() != null && !linkItem.getLinkRecordId().equals("")) {

                                            String[] originLinkRecordIds = linkItem.getLinkRecordId().split(",");
                                            String[] originLinkValues = linkItem.getValue().split(",");
                                            String[] originLinkItemIds = linkItem.getLinkItemId().split(",");

                                            if (ArrayUtils.contains(originLinkRecordIds, noteRecord.getId().toString())) {
                                                //如果有就删掉，如果没有没有就无事发生
                                                List<String> recordIdList = Arrays.asList(originLinkRecordIds);
                                                List<String> arrRecordIdList = new ArrayList<String>(recordIdList);
                                                arrRecordIdList.remove(noteRecord.getId().toString());
                                                if (arrRecordIdList.size() > 0) {
                                                    linkItem.setLinkRecordId(String.join(",", arrRecordIdList));
                                                } else {
                                                    linkItem.setLinkRecordId("");
                                                }

                                                //value同样的处理方式
                                                List<String> valueList = Arrays.asList(originLinkValues);
                                                List<String> arrValueList = new ArrayList<String>(valueList);
                                                arrValueList.remove(noteRecord.getName());
                                                if (arrValueList.size() > 0) {
                                                    linkItem.setValue(String.join(",", arrValueList));
                                                } else {
                                                    linkItem.setValue("");
                                                }

                                                //linkitemid同样的处理
                                                List<String> itemIdList = Arrays.asList(originLinkItemIds);
                                                List<String> arrItemIdList = new ArrayList<String>(itemIdList);
                                                arrItemIdList.remove(item.getId().toString());
                                                if (arrItemIdList.size() > 0) {
                                                    linkItem.setLinkItemId(String.join(",", arrItemIdList));
                                                } else {
                                                    linkItem.setLinkItemId("");
                                                }

                                            }
                                        }
                                        noteDwtableItemMapper.updateNoteDwtableItem(linkItem);
                                    }
                                } else {

                                }

                                //将源数据item中的关联信息都清除
                                item.setLinkRecordId("");
                                item.setLinkItemId("");
                                //处理完oldRecord之后开始更新关联的item
                                if (updateIds != null && updateIds.length > 0 && !updateIds[0].equals("")) {
                                    for (int i = 0; i < updateIds.length; i++) {
                                        NoteDwtableItem queryItem = new NoteDwtableItem();
                                        queryItem.setRecordId(Long.parseLong(updateIds[i]));
                                        queryItem.setColumnId(Long.parseLong(linkColumnId));
                                        NoteDwtableItem linkItem = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItem);
                                        //被关联的linkItem的linkrecordid里没有原纪录的id才会进行更新，如果有就无事发生
                                        if (linkItem.getLinkRecordId() == null || linkItem.getLinkRecordId().equals("")) {
                                            //被关联的这个item没有这边的信息，那就直接加上把
                                            linkItem.setLinkRecordId(updateIds[i]);
                                            linkItem.setLinkItemId(item.getId().toString());
                                            linkItem.setLinkColumnId(Long.parseLong(columnId));
                                            //这里要去取
                                            linkItem.setValue(noteRecord.getName());
                                            //同时给源数据的记录中加上被关联的记录id
                                            if (item.getLinkRecordId() == null || item.getLinkRecordId().equals("")) {
                                                item.setLinkRecordId(updateIds[i]);
                                            } else {
                                                item.setLinkRecordId(item.getLinkRecordId() + "," + updateIds[i]);
                                            }
                                            //这是给源数据的记录中加上被关联的数据的id
                                            if (item.getLinkItemId() == null || item.getLinkItemId().equals("")) {
                                                item.setLinkItemId(linkItem.getId().toString());
                                            } else {
                                                item.setLinkItemId(item.getLinkItemId() + "," + linkItem.getId().toString());
                                            }


                                        } else {
                                            String[] originLinkRecordIds = linkItem.getLinkRecordId().split(",");

                                            List<String> recordIdList = Arrays.asList(originLinkRecordIds);
                                            List<String> arrList = new ArrayList<String>(recordIdList);
                                            arrList.remove(noteRecord.getId().toString());
                                            if (arrList.size() > 0) {
                                                linkItem.setLinkRecordId(String.join(",", arrList));
                                            } else {
                                                linkItem.setLinkRecordId("");
                                            }

                                            //同时给源数据的记录中加上被关联的记录id
                                            if (item.getLinkRecordId() == null || item.getLinkRecordId().equals("")) {
                                                item.setLinkRecordId(updateIds[i]);
                                            } else {
                                                //被关联得记录ID里面有这个id，那就无事发生，没有就补充上去
                                                String[] itemRecordIds = item.getLinkRecordId().split(",");
                                                if (!ArrayUtils.contains(itemRecordIds, updateIds[i])) {
                                                    item.setLinkRecordId(item.getLinkRecordId() + "," + updateIds[i]);
                                                }

                                            }
                                            //这是给被关联的哪些记录的双向链接数据中增加源数据的id
                                            if (linkItem.getLinkItemId() == null || linkItem.getLinkItemId().equals("")) {
                                                linkItem.setLinkItemId(item.getId().toString());
                                            } else {
                                                linkItem.setLinkItemId(linkItem.getLinkItemId() + "," + item.getId().toString());
                                            }

                                            if (linkItem.getValue() == null || linkItem.getValue().equals("")) {
                                                linkItem.setValue(noteRecord.getName());
                                            } else {
                                                if (!ArrayUtils.contains(linkItem.getValue().split(","), noteRecord.getName())) {
                                                    linkItem.setValue(linkItem.getValue() + "," + noteRecord.getName());
                                                }
                                            }
                                            //这是给源数据的记录中加上被关联的数据的id
                                            if (item.getLinkItemId() == null || item.getLinkItemId().equals("")) {
                                                item.setLinkItemId(linkItem.getId().toString());
                                            } else {
                                                item.setLinkItemId(item.getLinkItemId() + "," + linkItem.getId().toString());
                                            }

                                        }
                                        noteDwtableItemMapper.updateNoteDwtableItem(linkItem);

                                    }
                                }

                                //修改完成
                            }
                        }
                    } else if (queryColumn.getType() == 25L) {
                        //todo:我计划在列类型中加一个新的东西,作为笔记和表格之间的链接,
                        // 第一版的方案是作为一个新的列类型,type=25,
                        //  但是这样会有一个问题,同一个block中绑定多个同一table的链接后,
                        //  想删除和修改的时候没办法精确地定位到具体是block中多个被选择的文本中的哪一个
                        //  故此我想创建一个新的对象叫做doubleLink ,用来存储双向链接,
                        //  先期就原有的type=21和25和doubleLink公用,就做冗余处理,
                        //  后期重构后要以doubleLink对象为主(或者到时候能设计出更好的数据结构来存储)
                        //
                        //  咳咳咳,东西有了但是有点偏差,(人甚至不能共情昨天的自己)
                        //  新的数据结构被我设计出来了但是名字是叫noteLink,存了笔记和block的id
                        //  以及链接的多维表格和数据表,行,列,单元格的id和对应的value
                        //  那么下一步就是找到所有会影响到这个对象的接口,目前我感觉会改动的地方有
                        //  列的新增和修改,行的新增和修改,linkToDwtable以及removeLink这些针对性的接口
                        //  这一次先用笔记和表格的链接作为切入点,如果以后双链家族扩展了新的类型和功能
                        //  就在这个noteLink对象的基础上扩容字段把.
//                        NoteNotelink noteNotelink = new NoteNotelink();
//                        noteNotelink.setBlockId();

                    } else if (queryColumn.getType() == 26L) {
                        //type=26是lookup功能

                        //这里要做一个新的功能，即飞书/notion里面的lookup功能，
                        //lookup功能核心原理其实是在双向链接的基础上，根据双向链接的所在行的ID，查找到同一行中其他列的信息然后进行展示
                        //原则上来说应该是任何列都可以使用lookup，但是根据老板的实际工作场景，决定在多维表格中的lookup功能限定在双向链接
                        //的基础上，即，先有双向链接，再根据双向链接去新增lookup列，这个要在新建的时候就限制住，即检查是否存在双向链接
                        //如果有双向链接才可以新建lookup列。
                        //在实现上，我的设计为：将lookup作为一个新的列类型，type暂定为26。它的逻辑应该是，在列的property里面存储类似于
                        //{"multiple":true,"back_field_id":2825,"back_field_name":"1","table_id":1545,"table_name":"数据表1",}
                        //但是要加入一些其他字段，比如：lookup_column_id表示lookup关联的列的id，在这个列下面的item里，根据这个lookup_column_id
                        //去查询到对应的Item内容，然后将这些value当做这个列下面的item的内容渲染出来（也就是这个列下面本身没有value的增删改查，
                        //单纯只是查询的结果


                        //来了，修改的时候，要根据items里面的内容来对item的相关信息进行修改（借用双向链接的linkRecordId等）
                        //也就是要更新每一个列类型是lookop的item所绑定的linkRecordId

                        //讨论过后认为第一版先出简单的，同时因为更新数据的频率比查询数据的频率低很多，所以把数据更新的逻辑放在源数据的更新后
                        //通过关系表来做级联更新（通过更新关系表来查找，触发事件是记录更新也就是本接口）
                        }
                    }
                    noteDwtableItemMapper.updateNoteDwtableItem(item);
                }
            }
            return noteRecord.getId().intValue();
        }


    /**
     * 修改记录排序
     *
     * @param noteRecord 记录
     * @return 结果
     */
    @Override
    public int updateSort(NoteRecord noteRecord,List<Map<String, Object>> sorts){

        int result = 1;
        //对sorts做处理
        for(Map<String, Object> data:sorts){
            if(data.get("id")!=null && !data.get("id").equals("")){
                Long recordId = Long.parseLong(data.get("id").toString());
                NoteRecord record = noteRecordMapper.selectNoteRecordById(recordId);
                record.setSort(Long.parseLong(data.get("sort").toString()));
                noteRecordMapper.updateNoteRecord(record);
                result = record.getId().intValue();
            }
        }
//        if(sorts.size()==2 && !sorts.get(0).get("id").equals("")&& !sorts.get(1).get("id").equals("")&& !sorts.get(1).get("id").equals(sorts.get(0).get("id"))){
//            //交换的两条数据id都存在且不相等时，交换两条数据的sort。
//            NoteRecord record1 = noteRecordMapper.selectNoteRecordById(Long.parseLong(sorts.get(0).get("id").toString()));
//            NoteRecord record2 = noteRecordMapper.selectNoteRecordById(Long.parseLong(sorts.get(1).get("id").toString()));
//        }

        return result;
    }


    /**
     * 批量删除记录
     * 
     * @param ids 需要删除的记录主键
     * @return 结果
     */
    @Override
    public int deleteNoteRecordByIds(String[] ids)
    {
        return noteRecordMapper.deleteNoteRecordByIds(ids);
    }

    /**
     * 删除记录信息
     * 
     * @param id 记录主键
     * @return 结果
     */
    @Override
    public int deleteNoteRecordById(Long id)
    {
        //删除记录是级联操作，需要先删除记录里面的数据信息
        // noteDwtableItemMapper.deleteNoteDwtableItemByRecordId(id);
        //查出行下面所有item,删掉这些item,并查找出所有linkRecordId包含id的item
        //将这些item的linkRecordId字段化为数组然后去掉id再更新一下这个item的linkrecordid

        NoteDwtableItem queryDwtableItem = new NoteDwtableItem();
        queryDwtableItem.setLinkRecordId(id.toString());
        List<NoteDwtableItem> listItems =  noteDwtableItemMapper.selectNoteDwtableItemList(queryDwtableItem);
        for (NoteDwtableItem noteDwtableItem : listItems) {
            String[] linkRecordIds = noteDwtableItem.getLinkRecordId().split(",");
            List<String> newLinkRecordIds = new ArrayList<>(Arrays.asList(linkRecordIds));
            for (String linkRecordId : newLinkRecordIds) {
                if(!linkRecordId.equals(id.toString())){
                    newLinkRecordIds.remove(linkRecordId);
                    noteDwtableItem.setLinkRecordId(newLinkRecordIds.toString());
                    noteDwtableItemMapper.updateNoteDwtableItem(noteDwtableItem);
                }
            }
        }
        noteDwtableItemMapper.deleteNoteDwtableItemByRecordId(id);
        
        return noteRecordMapper.deleteNoteRecordById(id);
    }

    /**
     * 查询记录内的表格数据
     *
     * @param id 记录主键
     * @return 记录
     */
    @Override
    public Map<String,Object> selectNoteRecordData(Long id) {
        //根据记录id查出这一行的所有数据
        NoteDwtableItem noteDwtableItem = new NoteDwtableItem();
        noteDwtableItem.setRecordId(id);
        List<NoteDwtableItem> dwtableItemList =  noteDwtableItemMapper.selectNoteDwtableItemList(noteDwtableItem);
        if(dwtableItemList.size()==0){
            return null;
        }

        //再根据数据中的列id查出所属列的列名称，与列id组成columnsmap
        NoteColumn noteColumn = new NoteColumn();
        noteColumn.setDwtableId(dwtableItemList.get(0).getDwtId());
        List<NoteColumn> noteColumnList =  noteColumnMapper.selectNoteColumnList(noteColumn);

        Map<Long, Object> columns = noteColumnList.stream().collect
                (Collectors.toMap(NoteColumn::getId,NoteColumn::getName));
        //用列名称和数据value组成tableDatamap
        //将item的id返回
        Map<Long,Long> itemId = new HashMap<>();
        Map<Long,Object> tableData = new HashMap<>();
        Map<Long,Object> linkRecordId = new HashMap<>();
        Map<Long,Object> linkBlockId = new HashMap<>();
        Map<Long,Object> linkNoteId = new HashMap<>();
        for (NoteDwtableItem item: dwtableItemList) {
            NoteColumn itemColumn = noteColumnMapper.selectNoteColumnById(item.getColumnId());
            if(columns.get(item.getColumnId())!=null){
                if(itemColumn.getType()==21L){
                    if(item.getLinkRecordId()!=null&&!"".equals(item.getLinkRecordId())){
                        //获取被链接的那条记录的name
                        String[] ids = item.getLinkRecordId().split(",");
                        List<NoteRecord> noteRecords = noteRecordMapper.selectNoteRecordByIds(ids);
                        String recordNames = "";
                        for (int i = 0;i<noteRecords.size();i++){
                            recordNames = recordNames+noteRecords.get(i).getName();
                            if(i<noteRecords.size()-1){
                                recordNames = recordNames + ",";
                            }
                        }
                        tableData.put(item.getColumnId(),recordNames);
                    }else{
                        tableData.put(item.getColumnId(),"");
                    }
                }else if(itemColumn.getType()==26L){
                    Object lookupResult = resolveLookupValue(itemColumn, id);
                    tableData.put(item.getColumnId(), lookupResult);
                }else {
                    tableData.put(item.getColumnId(),item.getValue());

                }
//                tableData.put(item.getColumnId(),item.getValue());
                linkRecordId.put(item.getColumnId(),item.getLinkRecordId());
                linkBlockId.put(item.getColumnId(),item.getLinkBlockId());
                linkNoteId.put(item.getColumnId(),item.getLinkNoteId());
                itemId.put(item.getColumnId(),item.getId());
            }
        }
        Map<String,Object> result = new HashMap<>();
        result.put("columns",columns);
        result.put("tableData",tableData);
        result.put("itemId",itemId);
        result.put("linkRecordId",linkRecordId);
        result.put("linkBlockId",linkBlockId);
        result.put("linkNoteId",linkNoteId);
        result.put("id",id);
        return result;
    }

    @Override
    public List<Map<String, Object>> selectNoteRecordDataList(NoteRecordVo noteRecordVo) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();

        List<NoteRecord> recordList = noteRecordMapper.selectNoteRecordList(noteRecordVo);
        for (NoteRecord record:recordList) {
            if(selectNoteRecordData(record.getId())!=null){
                result.add(selectNoteRecordData(record.getId()));
            }
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> selectDataListByDwtableId(Long dwtableId) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        NoteView noteView = new NoteView();
        noteView.setDwtableId(dwtableId);
        List<NoteView> viewList = noteViewMapper.selectNoteViewList(noteView);
        if(viewList.size()>0){
            for (NoteView view:viewList){
                NoteRecordVo noteRecord = new NoteRecordVo();
                noteRecord.setViewId(view.getId());
                List<NoteRecord> recordList = noteRecordMapper.selectNoteRecordList(noteRecord);
                if(recordList.size()>0){
                    for (NoteRecord record:recordList) {
                        if(selectNoteRecordData(record.getId())!=null){
                            result.add(selectNoteRecordData(record.getId()));
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * lookup解析结果封装类
     */
    public static class LookupResult
    {
        private String value;
        private String linkRecordId;

        public LookupResult(String value, String linkRecordId)
        {
            this.value = value;
            this.linkRecordId = linkRecordId;
        }

        public String getValue()
        {
            return value;
        }

        public String getLinkRecordId()
        {
            return linkRecordId;
        }
    }

    /**
     * 集合运算中获取lookup列(type=26)的linkRecordId列表。
     * lookup列自身item不存储linkRecordId，需通过double_link_column_id查关联的双向链接列item。
     *
     * 当double_link_column_id指向当前修改的item（直接或经back_field_id配对）时，
     * 当前item尚未入库（集合运算早于item入库，见updateNoteRecord末尾），
     * DB查询会拿到旧数据，必须改用itemMap的新数据；否则查DB。
     *
     * @param lookupColumn       lookup列对象(type=26)
     * @param currentColumnId    当前修改的列ID
     * @param currentBackFieldId 当前修改列的back_field_id（间接匹配用），可为空串
     * @param itemMap            当前修改的item数据（含新recordId）
     * @param recordId           当前行记录ID
     * @return linkRecordId列表；当DB查询无数据需跳过本次运算时返回null
     */
    private List<String> getLookupLinkRecordIds(NoteColumn lookupColumn, String currentColumnId,
            String currentBackFieldId, Map<String, Object> itemMap, Long recordId)
    {
        JSONObject prop = JSONObject.parseObject(lookupColumn.getProperty());
        String doubleLinkColumnId = prop.get("double_link_column_id").toString();

        if ((currentColumnId.equals(doubleLinkColumnId) || currentBackFieldId.equals(doubleLinkColumnId))
                && itemMap.get("recordId") != null && !"".equals(itemMap.get("recordId").toString()))
        {
            // 当前修改item尚未入库，必须用itemMap新数据
            List<String> ids = new ArrayList<>(Arrays.asList(itemMap.get("recordId").toString().split(",")));
            log.info("[SET-OP-LOOKUP] useItemMap columnId={}, linkRecordIds={}", lookupColumn.getId(), ids);
            return ids;
        }

        NoteDwtableItem query = new NoteDwtableItem();
        query.setRecordId(recordId);
        query.setLinkColumnId(Long.valueOf(doubleLinkColumnId));
        NoteDwtableItem linkItem = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(query);
        if (linkItem == null || linkItem.getLinkRecordId() == null || "".equals(linkItem.getLinkRecordId()))
        {
            return null;
        }
        List<String> ids = new ArrayList<>(Arrays.asList(linkItem.getLinkRecordId().split(",")));
        log.info("[SET-OP-LOOKUP] useDB columnId={}, linkRecordIds={}", lookupColumn.getId(), ids);
        return ids;
    }

    /**
     * 将LookupResult列表转为value列表，保持顺序与linkRecordId一一对应。
     */
    private List<String> toLookupValues(List<LookupResult> results)
    {
        List<String> values = new ArrayList<>(results.size());
        for (LookupResult lr : results)
        {
            values.add(lr.getValue());
        }
        return values;
    }

    /**
     * 解析lookup列(type=26)的值（核心方法）
     * 输入已知的linkRecordId列表，只做value解析，返回每个linkRecordId对应的LookupResult
     *
     * @param itemColumn    lookup列对象
     * @param linkRecordIds 已知的关联记录ID列表
     * @return 每个linkRecordId对应的LookupResult列表，无法解析时返回空列表
     */
    public List<LookupResult> resolveLookupValues(NoteColumn itemColumn, List<String> linkRecordIds)
    {
        List<LookupResult> results = new ArrayList<>();
        if (linkRecordIds == null || linkRecordIds.isEmpty())
        {
            return results;
        }

        JSONObject jsonObject = JSONObject.parseObject(itemColumn.getProperty());
        String sourceColumnId = jsonObject.get("source_column_id").toString();
        log.info("[RESOLVE-LOOKUP] columnId={}, sourceColumnId={}, linkRecordIds={}", itemColumn.getId(), sourceColumnId, linkRecordIds);

        // 沿着source_column_id链向下追踪，直到找到非lookup列（type != 26）
        NoteColumn sourceColumn = noteColumnMapper.selectNoteColumnById(Long.valueOf(sourceColumnId));
        int depth = 0;
        while (sourceColumn != null && sourceColumn.getType() == 26L && depth < 5)
        {
            JSONObject sourceProp = JSONObject.parseObject(sourceColumn.getProperty());
            sourceColumnId = sourceProp.get("source_column_id").toString();
            sourceColumn = noteColumnMapper.selectNoteColumnById(Long.valueOf(sourceColumnId));
            depth++;
        }
        log.info("[RESOLVE-LOOKUP] finalSourceColumnId={}, sourceColumnType={}, depth={}", sourceColumnId, sourceColumn != null ? sourceColumn.getType() : "null", depth);

        if (sourceColumn == null || depth >= 5)
        {
            return results;
        }

        // 对每个linkRecordId查找对应记录在最终sourceColumn下的value
        for (String recordId : linkRecordIds)
        {
            NoteDwtableItem lookupQueryItem = new NoteDwtableItem();
            lookupQueryItem.setRecordId(Long.valueOf(recordId));
            lookupQueryItem.setColumnId(Long.valueOf(sourceColumnId));
            NoteDwtableItem lookupValueItem = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(lookupQueryItem);
            String value = (lookupValueItem != null && lookupValueItem.getValue() != null) ? lookupValueItem.getValue() : "";
            log.info("[RESOLVE-LOOKUP] recordId={}, sourceColumnId={}, value={}", recordId, sourceColumnId, value);
            results.add(new LookupResult(value, recordId));
        }
        // 去重：当property的dedupe为true时，按value去重，保留首次出现的LookupResult（含其linkRecordId），保持原顺序
        Boolean dedupe = jsonObject.getBoolean("dedupe");
        if (Boolean.TRUE.equals(dedupe) && !results.isEmpty())
        {
            LinkedHashMap<String, LookupResult> deduped = new LinkedHashMap<>();
            for (LookupResult r : results)
            {
                deduped.putIfAbsent(r.getValue(), r);
            }
            results = new ArrayList<>(deduped.values());
        }
        log.info("[RESOLVE-LOOKUP] results={}", results);
        return results;
    }

    /**
     * 解析lookup列(type=26)的值
     * 根据关联的double_link_column_id查找lookup对应行的数据，
     * 支持sourceColumn也是lookup列时的链式解析（沿source_column_id链追踪到最终非lookup列）
     *
     * @param itemColumn lookup列对象
     * @param recordId   当前行记录ID
     * @return lookup解析后的值，无法解析时返回空字符串
     */
    public Object resolveLookupValue(NoteColumn itemColumn, Long recordId)
    {
        JSONObject jsonObject = JSONObject.parseObject(itemColumn.getProperty());
        String doubleLinkColumnId = jsonObject.get("double_link_column_id").toString();

        // 根据双向链接列查找关联的双向链接item，获取linkRecordId
        NoteDwtableItem queryDoubleLinkItem = new NoteDwtableItem();
        queryDoubleLinkItem.setRecordId(recordId);
        queryDoubleLinkItem.setLinkColumnId(Long.valueOf(doubleLinkColumnId));
        NoteDwtableItem doubleLinkItem = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryDoubleLinkItem);
        if (doubleLinkItem == null || doubleLinkItem.getLinkRecordId() == null || "".equals(doubleLinkItem.getLinkRecordId()))
        {
            return "";
        }

        // 委托给resolveLookupValues
        List<String> linkRecordIds = Arrays.asList(doubleLinkItem.getLinkRecordId().split(","));
        List<LookupResult> results = resolveLookupValues(itemColumn, linkRecordIds);
        if (results.isEmpty())
        {
            return "";
        }

        StringBuilder lookupValue = new StringBuilder();
        for (int i = 0; i < results.size(); i++)
        {
            lookupValue.append(results.get(i).getValue());
            if (i < results.size() - 1)
            {
                lookupValue.append(",");
            }
        }
        return lookupValue.toString();
    }

    /**
     * 全量重算指定lookup列(type=26)所有记录的存储值。
     * 在updateNoteColumn检测到dedupe开关变化时触发，遍历该列所属表的所有记录，
     * 重新调用resolveLookupValue并更新对应NoteDwtableItem的value。
     * 单条记录失败不中断整体流程，记录错误日志后继续。
     *
     * @param lookupColumn 需要重算的lookup列
     */
    @Override
    public void recomputeLookupColumnValues(NoteColumn lookupColumn)
    {
        NoteRecordVo queryRecord = new NoteRecordVo();
        queryRecord.setDwtableId(lookupColumn.getDwtableId());
        List<NoteRecord> records = noteRecordMapper.selectNoteRecordList(queryRecord);
        log.info("[RECOMPUTE-LOOKUP] start columnId={}, dwtableId={}, recordCount={}",
                lookupColumn.getId(), lookupColumn.getDwtableId(), records.size());

        for (NoteRecord record : records)
        {
            try
            {
                Object newValueObj = resolveLookupValue(lookupColumn, record.getId());
                String newValue = newValueObj == null ? "" : newValueObj.toString();

                NoteDwtableItem queryItem = new NoteDwtableItem();
                queryItem.setRecordId(record.getId());
                queryItem.setColumnId(lookupColumn.getId());
                NoteDwtableItem resultItem = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItem);

                if (resultItem == null)
                {
                    resultItem = new NoteDwtableItem();
                    resultItem.setRecordId(record.getId());
                    resultItem.setColumnId(lookupColumn.getId());
                    resultItem.setDwtId(lookupColumn.getDwtableId());
                    resultItem.setValue(newValue);
                    noteDwtableItemMapper.insertNoteDwtableItem(resultItem);
                }
                else
                {
                    resultItem.setValue(newValue);
                    noteDwtableItemMapper.updateNoteDwtableItem(resultItem);
                }
            }
            catch (Exception e)
            {
                log.error("[RECOMPUTE-LOOKUP] 重算失败 columnId={}, recordId={}",
                        lookupColumn.getId(), record.getId(), e);
            }
        }
        log.info("[RECOMPUTE-LOOKUP] done columnId={}", lookupColumn.getId());
    }

    /**
     * 从DB读取指定列在指定记录上的linkRecordId和value数据。
     * lookup列(type=26)通过double_link_column_id查关联item，再调resolveLookupValues解析（含dedupe）。
     * 双向关联列(type=21)直接从item读linkRecordId和value。
     *
     * @param column  列对象
     * @param recordId 记录ID
     * @param recordIds 输出参数：linkRecordId列表
     * @param values    输出参数：value列表（与recordIds索引对齐）
     * @return true=有数据，false=无数据（应跳过本次运算）
     */
    private boolean fetchColumnDataFromDB(NoteColumn column, Long recordId,
            List<String> recordIds, List<String> values)
    {
        if (column.getType() == 26L)
        {
            JSONObject prop = JSONObject.parseObject(column.getProperty());
            String doubleLinkColumnId = prop.get("double_link_column_id").toString();
            NoteDwtableItem query = new NoteDwtableItem();
            query.setRecordId(recordId);
            query.setLinkColumnId(Long.valueOf(doubleLinkColumnId));
            NoteDwtableItem linkItem = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(query);
            if (linkItem == null || linkItem.getLinkRecordId() == null || "".equals(linkItem.getLinkRecordId()))
            {
                return false;
            }
            List<String> linkIds = new ArrayList<>(Arrays.asList(linkItem.getLinkRecordId().split(",")));
            List<LookupResult> results = resolveLookupValues(column, linkIds);
            recordIds.addAll(results.stream().map(LookupResult::getLinkRecordId).collect(Collectors.toList()));
            values.addAll(toLookupValues(results));
        }
        else
        {
            NoteDwtableItem query = new NoteDwtableItem();
            query.setRecordId(recordId);
            query.setColumnId(column.getId());
            NoteDwtableItem item = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(query);
            if (item == null || item.getLinkRecordId() == null || "".equals(item.getLinkRecordId())
                    || item.getValue() == null)
            {
                return false;
            }
            recordIds.addAll(Arrays.asList(item.getLinkRecordId().split(",")));
            values.addAll(Arrays.asList(item.getValue().split(",")));
        }
        return true;
    }

    /**
     * 全量重算引用了指定lookup列的所有集合运算列。
     */
    @Override
    public void recomputeSetOperationsForLookup(NoteColumn lookupColumn)
    {
        List<NoteColumn> setColumns = noteColumnMapper.selectSetColumnByDwtId(lookupColumn.getDwtableId());
        String lookupColumnIdStr = lookupColumn.getId().toString();
        NoteRecordVo queryRecord = new NoteRecordVo();
        queryRecord.setDwtableId(lookupColumn.getDwtableId());
        List<NoteRecord> records = noteRecordMapper.selectNoteRecordList(queryRecord);

        for (NoteColumn setColumn : setColumns)
        {
            try
            {
                JSONObject setProp = JSONObject.parseObject(setColumn.getProperty());
                String columnAId = setProp.get("columnAId").toString();
                String columnBId = setProp.get("columnBId").toString();
                String calcType = setProp.get("calcType").toString();

                if (!columnAId.equals(lookupColumnIdStr) && !columnBId.equals(lookupColumnIdStr))
                {
                    continue;
                }

                NoteColumn columnA = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnAId));
                NoteColumn columnB = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnBId));
                if (columnA == null || columnB == null)
                {
                    continue;
                }

                log.info("[RECOMPUTE-SET-OP] start setColumnId={}, lookupColumnId={}, recordCount={}",
                        setColumn.getId(), lookupColumn.getId(), records.size());

                for (NoteRecord record : records)
                {
                    try
                    {
                        computeSetOperationForRecord(setColumn, columnA, columnB, calcType, record.getId());
                    }
                    catch (Exception e)
                    {
                        log.error("[RECOMPUTE-SET-OP] 重算失败 setColumnId={}, recordId={}",
                                setColumn.getId(), record.getId(), e);
                    }
                }
            }
            catch (Exception e)
            {
                log.error("[RECOMPUTE-SET-OP] setColumn处理失败 setColumnId={}", setColumn.getId(), e);
            }
            log.info("[RECOMPUTE-SET-OP] done setColumnId={}", setColumn.getId());
        }
    }

    /**
     * 全量重算指定集合运算列(type=24)所有记录的存储值（Excel 导入 U5，KTD8 重算编排入口）。
     * <p>
     * {@link #recomputeSetOperationsForLookup} 以 lookup 列为键筛选集合运算列；
     * 目标表中 columnA/B 直接引用导入 18/21 关联列的集合运算列无现成批量方法，
     * 本方法为其等价入口：解析 property 取 A/B 列与计算类型，逐记录调
     * {@link #computeSetOperationForRecord}（与 {@code updateNoteRecord} 内联集合运算块等价，
     * 从 DB 读取 A/B 数据）。单条记录失败不中断整体流程，记录错误日志后继续。
     *
     * @param setColumn 需要重算的集合运算列
     */
    @Override
    public void recomputeSetOperationColumn(NoteColumn setColumn)
    {
        if (setColumn == null || setColumn.getId() == null)
        {
            return;
        }
        try
        {
            JSONObject setProp = JSONObject.parseObject(setColumn.getProperty());
            String columnAId = setProp.get("columnAId").toString();
            String columnBId = setProp.get("columnBId").toString();
            String calcType = setProp.get("calcType").toString();

            NoteColumn columnA = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnAId));
            NoteColumn columnB = noteColumnMapper.selectNoteColumnById(Long.parseLong(columnBId));
            if (columnA == null || columnB == null)
            {
                log.warn("[RECOMPUTE-SET-OP-COL] 参与运算列缺失，跳过 setColumnId={}, columnAId={}, columnBId={}",
                        setColumn.getId(), columnAId, columnBId);
                return;
            }

            NoteRecordVo queryRecord = new NoteRecordVo();
            queryRecord.setDwtableId(setColumn.getDwtableId());
            List<NoteRecord> records = noteRecordMapper.selectNoteRecordList(queryRecord);
            log.info("[RECOMPUTE-SET-OP-COL] start setColumnId={}, dwtableId={}, recordCount={}",
                    setColumn.getId(), setColumn.getDwtableId(), records.size());

            for (NoteRecord record : records)
            {
                try
                {
                    computeSetOperationForRecord(setColumn, columnA, columnB, calcType, record.getId());
                }
                catch (Exception e)
                {
                    log.error("[RECOMPUTE-SET-OP-COL] 重算失败 setColumnId={}, recordId={}",
                            setColumn.getId(), record.getId(), e);
                }
            }
        }
        catch (Exception e)
        {
            log.error("[RECOMPUTE-SET-OP-COL] setColumn处理失败 setColumnId={}", setColumn.getId(), e);
        }
        log.info("[RECOMPUTE-SET-OP-COL] done setColumnId={}", setColumn.getId());
    }

    /**
     * 对单条记录执行集合运算并 upsert 结果 item（recomputeSetOperationsForLookup 与
     * recomputeSetOperationColumn 的共享实现，等价于 updateNoteRecord 内联集合运算块的 DB 读取路径）。
     *
     * @param setColumn 集合运算列
     * @param columnA   A 列（21 双向关联或 26 lookup）
     * @param columnB   B 列（21 双向关联或 26 lookup）
     * @param calcType  计算类型（disjunction/subtract/intersection/union）
     * @param recordId  目标记录 id
     */
    private void computeSetOperationForRecord(NoteColumn setColumn, NoteColumn columnA, NoteColumn columnB,
            String calcType, Long recordId)
    {
        List<String> aRecordIds = new ArrayList<>();
        List<String> aValues = new ArrayList<>();
        if (!fetchColumnDataFromDB(columnA, recordId, aRecordIds, aValues))
        {
            return;
        }

        List<String> bRecordIds = new ArrayList<>();
        List<String> bValues = new ArrayList<>();
        if (!fetchColumnDataFromDB(columnB, recordId, bRecordIds, bValues))
        {
            return;
        }

        Map<String, String> aMap = new LinkedHashMap<>();
        for (int i = 0; i < aRecordIds.size(); i++)
        {
            aMap.put(aRecordIds.get(i), i < aValues.size() ? aValues.get(i) : "");
        }
        Map<String, String> bMap = new LinkedHashMap<>();
        for (int i = 0; i < bRecordIds.size(); i++)
        {
            bMap.put(bRecordIds.get(i), i < bValues.size() ? bValues.get(i) : "");
        }

        List<String> rRecordIds = new ArrayList<>();
        if ("disjunction".equals(calcType))
        {
            rRecordIds = (List<String>) CollectionUtils.disjunction(aRecordIds, bRecordIds);
        }
        else if ("subtract".equals(calcType))
        {
            rRecordIds = (List<String>) CollectionUtils.subtract(aRecordIds, bRecordIds);
        }
        else if ("intersection".equals(calcType))
        {
            rRecordIds = (List<String>) CollectionUtils.intersection(aRecordIds, bRecordIds);
        }
        else if ("union".equals(calcType))
        {
            rRecordIds = (List<String>) CollectionUtils.union(aRecordIds, bRecordIds);
        }
        else
        {
            // 未知calcType，跳过本记录，避免清空已有结果
            log.warn("[RECOMPUTE-SET-OP] 未知calcType={} setColumnId={}, recordId={}",
                    calcType, setColumn.getId(), recordId);
            return;
        }

        List<String> rValues = new ArrayList<>();
        for (String id : rRecordIds)
        {
            String value = aMap.get(id);
            if (value == null)
            {
                value = bMap.get(id);
            }
            if (value == null)
            {
                value = "";
            }
            rValues.add(value);
        }

        NoteDwtableItem queryItem = new NoteDwtableItem();
        queryItem.setRecordId(recordId);
        queryItem.setColumnId(setColumn.getId());
        NoteDwtableItem resultItem = noteDwtableItemMapper.selectNoteDwtableItemByRecordAndColumn(queryItem);
        if (resultItem == null)
        {
            resultItem = new NoteDwtableItem();
            resultItem.setRecordId(recordId);
            resultItem.setColumnId(setColumn.getId());
            resultItem.setDwtId(setColumn.getDwtableId());
        }
        resultItem.setValue(String.join(",", rValues));
        resultItem.setLinkRecordId(String.join(",", rRecordIds));
        if (resultItem.getId() == null)
        {
            noteDwtableItemMapper.insertNoteDwtableItem(resultItem);
        }
        else
        {
            noteDwtableItemMapper.updateNoteDwtableItem(resultItem);
        }
    }

    /**
     * 派生记录名称：取该表最左侧 type=1（多行文本）列的值（R1, R6）。
     * 取值优先级（KTD-3）：先 incomingItems（本次写入新值），再 existingItems（DB 当前值），仍无则返回 ""。
     * 列表查询 selectNoteColumnList 已按 sort 排序，故首个匹配即为最左侧 type=1 列。
     *
     * @param dwtableId      数据表ID（必传）
     * @param incomingItems  本次写入的 items（Map 列表，可为 null）
     * @param existingItems  DB 当前 items（可为 null）
     * @return 派生的行名称；无 type=1 列或值为空则返回 ""
     */
    @Override
    public String deriveRecordName(Long dwtableId,
                                   List<Map<String, Object>> incomingItems,
                                   List<NoteDwtableItem> existingItems)
    {
        if (dwtableId == null)
        {
            return "";
        }
        // 查表所有列（已按 sort 排序），找首个 type==1 列
        NoteColumn queryColumn = new NoteColumn();
        queryColumn.setDwtableId(dwtableId);
        List<NoteColumn> columns = noteColumnMapper.selectNoteColumnList(queryColumn);
        Long sourceColumnId = null;
        for (NoteColumn col : columns)
        {
            if (col.getType() != null && col.getType() == 1L)
            {
                sourceColumnId = col.getId();
                break;
            }
        }
        if (sourceColumnId == null)
        {
            return "";
        }
        // 先从 incomingItems 取新值（KTD-3）
        if (incomingItems != null)
        {
            for (Map<String, Object> item : incomingItems)
            {
                Object colIdObj = item.get("columnId");
                if (colIdObj == null)
                {
                    continue;
                }
                Long colId;
                try
                {
                    colId = Long.parseLong(colIdObj.toString());
                }
                catch (NumberFormatException e)
                {
                    continue;
                }
                if (colId.equals(sourceColumnId))
                {
                    Object val = item.get("value");
                    return val == null ? "" : val.toString();
                }
            }
        }
        // 回退 existingItems（DB 当前值）
        if (existingItems != null)
        {
            for (NoteDwtableItem item : existingItems)
            {
                if (sourceColumnId.equals(item.getColumnId()))
                {
                    return item.getValue() == null ? "" : item.getValue();
                }
            }
        }
        return "";
    }

    /**
     * 按表重算所有记录的 name 字段（KTD-5 @Transactional，KTD-6 无条件重算幂等）。
     * 对该表每条记录调用 deriveRecordName(dwtableId, null, items) 重算 name 并 updateNoteRecord 落盘。
     * 单条记录失败不中断整体流程，记录错误日志后继续。
     *
     * @param dwtableId 数据表ID
     * @return 成功重算的记录数
     */
    @Override
    public int recomputeRecordNamesForTable(Long dwtableId)
    {
        if (dwtableId == null)
        {
            return 0;
        }
        NoteRecordVo queryRecord = new NoteRecordVo();
        queryRecord.setDwtableId(dwtableId);
        List<NoteRecord> records = noteRecordMapper.selectNoteRecordList(queryRecord);
        int updated = 0;
        for (NoteRecord record : records)
        {
            try
            {
                NoteDwtableItem queryItem = new NoteDwtableItem();
                queryItem.setRecordId(record.getId());
                List<NoteDwtableItem> items = noteDwtableItemMapper.selectNoteDwtableItemList(queryItem);
                String derivedName = deriveRecordName(dwtableId, null, items);
                record.setName(derivedName);
                noteRecordMapper.updateNoteRecord(record);
                updated++;
            }
            catch (Exception e)
            {
                log.error("[RECOMPUTE-NAME] 重算失败 dwtableId={}, recordId={}",
                        dwtableId, record.getId(), e);
            }
        }
        log.info("[RECOMPUTE-NAME] done dwtableId={}, recordCount={}, updated={}",
                dwtableId, records.size(), updated);
        return updated;
    }

    /**
     * 一次性回填所有数据表所有记录的 name（R5）。
     * 遍历全部数据表，逐表调用 recomputeRecordNamesForTable（每条 update 自动提交，单条失败不中断）。
     * 单表失败不中断整体流程，记录错误日志后继续。
     * 本方法不加 @Transactional（每表独立处理，避免一个大事务）。
     *
     * @return 所有表累计成功重算的记录数
     */
    @Override
    public int recomputeAllRecordNames()
    {
        List<NoteDwtable> tables = noteDwtableMapper.selectNoteDwtableList(new NoteDwtable());
        int totalUpdated = 0;
        for (NoteDwtable table : tables)
        {
            try
            {
                totalUpdated += recomputeRecordNamesForTable(table.getId());
            }
            catch (Exception e)
            {
                log.error("[BACKFILL-NAME] 表重算失败 dwtableId={}", table.getId(), e);
            }
        }
        log.info("[BACKFILL-NAME] done tableCount={}, totalUpdated={}", tables.size(), totalUpdated);
        return totalUpdated;
    }
}
