package com.ruoyi.system.service.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.file.ExcelImportUtils;
import com.ruoyi.system.domain.NoteBlock;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteMeta;
import com.ruoyi.system.domain.vo.NoteNoteVo;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.INoteRoleMenuService;
import org.apache.poi.ss.formula.functions.T;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.service.INoteNoteService;

import org.apache.poi.ss.usermodel.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import static com.ruoyi.common.utils.file.ExcelImportUtils.createObjectFromRow;


/**
 * 笔记Service业务层处理
 * 
 * @author ruoyi
 * @date 2023-03-09
 */
@Service
public class NoteNoteServiceImpl implements INoteNoteService
{
    private static final Logger log = LoggerFactory.getLogger(NoteNoteServiceImpl.class);

    @Autowired
    private NoteNoteMapper noteNoteMapper;

    @Autowired
    private NoteDwtableMapper noteDwtableMapper;
    @Autowired
    private NoteDwtableItemMapper noteDwtableItemMapper;
    @Autowired
    private NoteBlockMapper noteBlockMapper;
    @Autowired
    private NoteColumnMapper noteColumnMapper;
    @Autowired
    private NoteRecordMapper noteRecordMapper;
    @Autowired
    private NoteViewMapper noteViewMapper;


    @Autowired
    private NoteMetaMapper noteMetaMapper;
    @Autowired
    private SysUserMapper sysUserMapper;


    @Autowired
    private INoteRoleMenuService noteRoleMenuService;

    @Autowired
    private NoteDwtableServiceImpl noteDwtableServiceImpl;

    /**
     * 查询笔记
     * 
     * @param id 笔记主键
     * @return 笔记
     */
    @Override
    public NoteNote selectNoteNoteById(Long id)
    {
        return noteNoteMapper.selectNoteNoteById(id);
    }

    @Override
    public NoteMeta selectNoteDataById(Long noteId) {
        return noteMetaMapper.selectNoteMetaByNoteId(noteId);
    }

    /**
     * 查询笔记列表
     * 
     * @param noteNote 笔记
     * @return 笔记
     */
    @Override
    public List<NoteNote> selectNoteNoteList(NoteNote noteNote,Long userId)
    {
        List<NoteNote> noteList = null;
        // 管理员显示所有菜单信息
        if (SysUser.isAdmin(userId))
        {
            noteList = noteNoteMapper.selectAllMenuList();
        }
        else
        {
            //角色赋权的笔记
            noteNote.getParams().put("userId", userId);
            noteList = noteNoteMapper.selectNoteListByUserId(userId);
            //用户本身是作者的笔记
            List<NoteNote> authList = noteNoteMapper.selectNoteListByAuthId(userId) ;
            noteList.addAll(authList);
//            //加入根节点
//            List<NoteNote> rootNotes = noteNoteMapper.selectNoteDefaultNote();
//            noteList.addAll(rootNotes);
        }
        return noteList;
    }


    /**
     * 查询笔记系统菜单列表
     *
     * @param noteNote 笔记
     * @return 笔记
     */
    @Override
    public List<NoteNote> selectNoteMenuList(NoteNote noteNote,Long userId)
    {
        List<NoteNote> noteList = null;
        // 管理员显示所有菜单信息
        if (SysUser.isAdmin(userId))
        {
            noteList = noteNoteMapper.selectAllMenuList();
        }
        else
        {
            noteNote.getParams().put("userId", userId);
            noteList = noteNoteMapper.selectNoteMenuListByUserId(userId);
        }

        return noteList;
    }

    /**
     * 查询笔记系统菜单和按钮权限列表
     *
     * @param noteNote 笔记
     * @return 笔记
     */
    @Override
    public List<NoteNote> selectNoteMenuAuthList(NoteNote noteNote,Long userId)
    {
        List<NoteNote> noteList = null;
        // 管理员显示所有菜单信息
        if (SysUser.isAdmin(userId))
        {
            noteList = noteNoteMapper.selectAllMenuAuthList();
        }
        else
        {
            noteNote.getParams().put("userId", userId);
            noteList = noteNoteMapper.selectNoteMenuAuthListByUserId(userId);
        }

        return noteList;
    }


    /**
     * 查询用户拥有的数据权限
     *
     * @param noteNote 笔记
     * @return 笔记
     */
    @Override
    public List<NoteNote> selectUserNoteList(NoteNote noteNote,Long userId)
    {
        List<NoteNote> noteList = null;
        // 管理员显示所有菜单信息
        if (SysUser.isAdmin(userId))
        {
            noteList = noteNoteMapper.selectAllMenuList();
        }
        else
        {
            noteNote.getParams().put("userId", userId);
            noteList = noteNoteMapper.selectNoteListByUserId(userId);
        }

        return noteList;
    }



    /**
     * 查询多维笔记数据表
     *
     * @param id 多维笔记ID
     * @return 多维笔记数据表集合
     */
    @Override
    public List<NoteDwtable> selectDWTableList(Long id) {
        return noteDwtableMapper.selectDWTableList(id);
    }

    /**
     * 查询笔记回收站列表
     *
     * @param noteNote 笔记
     * @return 笔记
     */
    @Override
    public List<NoteNote> selectNoteGarbageList(NoteNote noteNote)
    {
        return noteNoteMapper.selectNoteGarbageList(noteNote);
    }



    /**
     * 查询模板列表
     *
     * @param noteNote 笔记
     * @return 笔记
     */
    @Override
    public List<NoteNote> selectNoteTemplateList(NoteNote noteNote)
    {
        return noteNoteMapper.selectNoteTemplateList(noteNote);
    }


    /**
     * 查询收藏列表
     *
     * @param noteNote 笔记
     * @return 笔记
     */
    @Override
    public List<NoteNote> selectCollectionList(NoteNote noteNote) {
        return noteNoteMapper.selectCollectionList(noteNote);

    }

    @Override
    public int importNote(InputStream is, String filePath) {
        Class<?> Class = null;
        try {
            //自己做一个吧
            filePath = "C:\\Users\\兔牙牙\\Desktop\\导入测试1.xlsx";
            ExcelImportUtils.importFromExcel(filePath,Class,1,null);

            int sheetIndex = 1;
            Map<Integer, String> mapping = new HashMap<>();
            mapping.put(0, "name");
            mapping.put(1, "age");
//            List<NoteNote> notes = ExcelImportUtils.importFromExcel(filePath, NoteNote.class, 0, mapping);


            // 读取工作簿
            Workbook workbook = WorkbookFactory.create(new File(filePath));
            Sheet sheet = workbook.getSheetAt(1);

            // 获取标题行（第一行）
            Row titleRow = sheet.getRow(0);
            int numberOfCells = titleRow.getLastCellNum();

            // 如果没有提供映射，则使用标题行的列名作为属性名（列索引->列名字符串）
            if (mapping == null) {
                mapping = new HashMap<>();
                for (int i = 0; i < numberOfCells; i++) {
                    Cell cell = titleRow.getCell(i);
                    String columnName = cell.getStringCellValue();
                    mapping.put(i, columnName);
                }
            }

            // 准备数据列表
            List<NoteNote> result = new ArrayList<>();

            // 从第二行开始遍历数据行
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                NoteNote obj = createObjectFromRow(row, NoteNote.class, mapping);
                if (obj != null) {
                    result.add(obj);
                }
            }

            workbook.close();


        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        //把结果result存入数据库表


        return 0;
    }


    /**
     * 新增笔记
     * 
     * @param noteNote 笔记
     * @return 结果
     */
    @Override
    public int insertNoteNote(NoteNote noteNote)
    {
        //初始化note
        if(noteNote.getDelFlag()==null){
            noteNote.setDelFlag(0L);
        }
        if(noteNote.getNoteType()==null){
            noteNote.setNoteType(1L);
        }
        noteNote.setRevisionId(1L);
        noteNote.setCollectionFlag(0L);
        if(noteNote.getAuth()==null){
            noteNote.setAuth(1L);
        }
        int result = noteNoteMapper.insertNoteNote(noteNote);
        if(result == 1){
            result = noteNote.getId().intValue();
        }

        //获取用户信息
        SysUser user = sysUserMapper.selectUserById(noteNote.getAuth());
        //新增笔记的同时去笔记元数据里新增对应信息
        if(noteNote.getNoteType()==2L){
            NoteMeta noteMeta = new NoteMeta();
            noteMeta.setNoteId(noteNote.getId());
            noteMeta.setCreateDate(new Date().toString());
//            noteMeta.setCreator("1");
//            noteMeta.setCreateName("admin");
            noteMeta.setCreator(user.getUserId().toString());
            noteMeta.setCreateName(user.getUserName());
            noteMeta.setDeleteFlag(0L);
            noteMeta.setIsPined(0L);
            noteMeta.setIsExternal(0L);
            noteMeta.setOwner("1");
            noteMeta.setIsStared(0L);
            noteMeta.setIsStared(0L);
            noteMeta.setObjType("doc");
            noteMeta.setType(2L);
            noteMeta.setUrl("");
            noteMeta.setTitle(noteNote.getTitle());
            noteMetaMapper.insertNoteMeta(noteMeta);

        }

        return result;
    }

    /**
     * 修改笔记
     * 
     * @param noteNote 笔记
     * @return 结果
     */
    @Override
    public int updateNoteNote(NoteNote noteNote)
    {

        //修改笔记的同时去笔记元数据里修改对应信息
        if(noteNote.getNoteType()==2L){
            NoteMeta noteMeta= new NoteMeta();
            NoteMeta queryNoteMeta = noteMetaMapper.selectNoteMetaByNoteId(noteNote.getId());
            SysUser user = sysUserMapper.selectUserById(noteNote.getAuth());
            if(user==null){
                user = sysUserMapper.selectUserById(1L);
            }
            if(queryNoteMeta==null){
                noteMeta.setNoteId(noteNote.getId());
                noteMeta.setCreator(user.getUserId().toString());
                noteMeta.setOwner(user.getUserId().toString());
                noteMeta.setCreateDate(new Date().toString());
                noteMeta.setCreateName(user.getUserName());
                noteMeta.setTitle(noteNote.getTitle());
                noteMeta.setEditName(user.getUserName());
                noteMeta.setEditDate(new Date().toString());
                noteMetaMapper.insertNoteMeta(noteMeta);
            }else{
                queryNoteMeta.setTitle(noteNote.getTitle());
                queryNoteMeta.setEditName(user.getUserName());
                queryNoteMeta.setEditDate(new Date().toString());
                noteMetaMapper.updateNoteMeta(queryNoteMeta);
            }

        }
        return noteNoteMapper.updateNoteNote(noteNote);
    }



    /**
     * 另存为新笔记
     *
     * @param noteNote 笔记
     * @return 结果
     */
    @Override
    public int saveAsNewNote(NoteNote noteNote)
    {


        //获取用户信息
        SysUser user = sysUserMapper.selectUserById(noteNote.getAuth());
        //另存为新笔记的同时去笔记元数据里新增对应信息
        if(noteNote.getNoteType()==2L){
            NoteMeta noteMeta = new NoteMeta();
            noteMeta.setNoteId(noteNote.getId());
            noteMeta.setCreateDate(new Date().toString());
            noteMeta.setCreator(user.getUserId().toString());
            noteMeta.setCreateName(user.getUserName());
            noteMeta.setDeleteFlag(0L);
            noteMeta.setIsPined(0L);
            noteMeta.setIsExternal(0L);
            noteMeta.setOwner("1");
            noteMeta.setIsStared(0L);
            noteMeta.setIsStared(0L);
            noteMeta.setObjType("doc");
            noteMeta.setType(2L);
            noteMeta.setUrl("");
            noteMeta.setTitle(noteNote.getTitle());
            noteMetaMapper.insertNoteMeta(noteMeta);

        }
        NoteNote originNote = noteNoteMapper.selectNoteNoteById(noteNote.getId());
        originNote.setId(null);
        originNote.setTemplateFlag(0L);
        originNote.setTitle(noteNote.getTitle());
        int result = noteNoteMapper.insertNoteNote(originNote);
        if(result == 1){
            result = originNote.getId().intValue();
        }
//        noteNoteMapper.updateNoteNote(noteNote);

        //另存为的同时将原note下相关联的block或者数据表、视图信息、列、记录、单元格等所有内容统一进行复制
        // （双向链接的关联信息也要同时更新，主要包括被关联的表中要有回显）
        if(noteNote.getNoteType()==2l){
            //笔记类型，需要将note下的所有block复制过去
            NoteBlock queryBlock = new NoteBlock();
            queryBlock.setParentId(noteNote.getId());
            List<NoteBlock> originBlocks = noteBlockMapper.selectNoteBlockList(queryBlock);
            List<NoteBlock> newBlocks = new ArrayList<NoteBlock>();
            if(originBlocks.size()>0){
                for (NoteBlock block:originBlocks) {
                    block.setParentId(originNote.getId());
                    newBlocks.add(block);
                }
                newBlocks.stream().forEach(newBlock ->noteBlockMapper.insertNoteBlock(newBlock) );
            }


        }else if(noteNote.getNoteType()==4l){

            //多维表格需要复制的内容包括数据表、视图、列、行、单元格
            NoteDwtable queryDwtable = new NoteDwtable();
            queryDwtable.setNoteId(noteNote.getId());
            List<NoteDwtable> originDwtables = noteDwtableMapper.selectNoteDwtableList(queryDwtable);
            //先复制数据表
            List<NoteDwtable> newDwtables = new ArrayList<NoteDwtable>();
            if(originDwtables.size()>0){
                for (NoteDwtable dwtable:originDwtables
                     ) {
                    dwtable.setNoteId(originNote.getId());
                    newDwtables.add(dwtable);

                }
            }


        }

        return result;
    }

    @Override
    public int updateFirstLogin(Long userId) {
        sysUserMapper.updateFirstLogin(userId);
        return 0;
    }


    /**
     * 递归收集文件夹所有子孙笔记（迭代式 DFS，跨 delFlag 状态查询）
     * 用于文件夹级联删除/恢复时收集子孙节点，保证文件夹与子孙状态一致
     *
     * @param folderId 文件夹 ID
     * @return 子孙 NoteNote 对象列表（不含 folderId 自身）
     */
    private List<NoteNote> collectDescendantNotes(Long folderId) {
        List<NoteNote> descendants = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(folderId);
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(folderId);
        while (!stack.isEmpty()) {
            Long currentId = stack.pop();
            List<NoteNote> children = noteNoteMapper.selectNoteNoteChildrenByParentId(currentId);
            if (children == null) {
                continue;
            }
            for (NoteNote child : children) {
                if (child == null || child.getId() == null) {
                    continue;
                }
                Long childId = child.getId();
                if (visited.contains(childId)) {
                    log.warn("Detected cycle in note hierarchy: noteId={} already visited when expanding parentId={}", childId, currentId);
                    continue;
                }
                visited.add(childId);
                descendants.add(child);
                stack.push(childId);
            }
        }
        return descendants;
    }


    /**
     * 批量删除笔记
     *
     * @param ids 需要删除的笔记主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteNoteNoteByIds(String[] ids)
    {
        List<String> allIds = new ArrayList<>(Arrays.asList(ids));
        for(String id: ids){
            Long noteId = Long.parseLong(id);
            NoteNote note= noteNoteMapper.selectNoteNoteById(noteId);
            if(note.getNoteType()==2L){
                //修改笔记的同时去笔记元数据里修改对应信息
                NoteMeta noteMeta = noteMetaMapper.selectNoteMetaByNoteId(noteId);
                if(noteMeta!=null){
                    noteMeta.setDeleteFlag(1L);
                    noteMetaMapper.updateNoteMeta(noteMeta);
                }
            }else if(note.getNoteType()==1L){
                // 文件夹：递归收集子孙，按各自 noteType 同步关联数据，合并进批量软删除
                List<NoteNote> descendants = collectDescendantNotes(noteId);
                for (NoteNote child : descendants) {
                    Long childId = child.getId();
                    // type=2 子孙：同步 NoteMeta.deleteFlag=1（与现有 type=2 分支一致）
                    if (child.getNoteType() == 2L) {
                        NoteMeta childMeta = noteMetaMapper.selectNoteMetaByNoteId(childId);
                        if (childMeta != null) {
                            childMeta.setDeleteFlag(1L);
                            noteMetaMapper.updateNoteMeta(childMeta);
                        }
                    }
                    // 对所有子孙软删 dwtable（与现有循环末尾对所有类型调用 deleteNoteDwtableByNoteId 一致）
                    noteDwtableMapper.deleteNoteDwtableByNoteId(childId);
                    allIds.add(childId.toString());
                }
            }
            //删除的同时要保证笔记下面的数据表等对象也一起被级联删除
            noteDwtableMapper.deleteNoteDwtableByNoteId(noteId);
        }
        noteNoteMapper.deleteNoteNoteByIds(allIds.toArray(new String[0]));


        return 1;
    }


    /**
     * 重置系统
     *
     * @return 结果
     */
    @Override
    public int removeAll()
    {
        //需要清空的表包括：meta、dwtable、view、record、item、block、column以及note中type！=1的所有数据
        noteMetaMapper.removeAll();
        noteDwtableMapper.removeAll();
        noteDwtableItemMapper.removeAll();
        noteViewMapper.removeAll();
        noteRecordMapper.removeAll();
        noteDwtableItemMapper.removeAll();
        noteBlockMapper.removeAll();
        noteColumnMapper.removeAll();
        noteNoteMapper.removeAll();
        noteNoteMapper.resettingIdTo50();

        return 0;
    }

    /**
     * 清除所有数据
     *
     * @return 结果
     */
    @Override
    public int removeAllData()
    {
        //需要清空的表包括：meta、dwtable、view、record、item、block、column以及note中type！=1的所有数据
        noteMetaMapper.removeAll();
        noteDwtableMapper.removeAll();
        noteDwtableItemMapper.removeAll();
        noteViewMapper.removeAll();
        noteRecordMapper.removeAll();
        noteDwtableItemMapper.removeAll();
        noteBlockMapper.removeAll();
        noteColumnMapper.removeAll();
        noteNoteMapper.removeAll();
        noteNoteMapper.resettingIdTo50();

        return 0;
    }


    /**
     * 彻底删除笔记
     *
     * @param ids 需要删除的笔记主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteNoteFromGarbageByIds(String[] ids) {

        List<String> allIds = new ArrayList<>(Arrays.asList(ids));
        for (String id:
             ids) {
            Long noteId = Long.parseLong(id);
            NoteNote note= noteNoteMapper.selectNoteNoteById(noteId);
            //如果是多维表格的话需要进行级联删除
            if(note.getNoteType()==4L){
                //查出所有数据表
                List<NoteDwtable> selectDWTableList = noteDwtableMapper.selectDWTableList(noteId);
                if(selectDWTableList.size()!=0){
                    //删除数据表
                    for (NoteDwtable dwtable:
                    selectDWTableList) {
                        noteDwtableServiceImpl.deleteNoteDwtableById(dwtable.getId());
                    }
                }
            }else if(note.getNoteType()==2L){
                //修改笔记的同时去笔记元数据里修改对应信息
                NoteMeta noteMeta = noteMetaMapper.selectNoteMetaByNoteId(noteId);
                if(noteMeta!=null){
                    noteMeta.setDeleteFlag(2L);
                    noteMetaMapper.updateNoteMeta(noteMeta);
                }


            }else if(note.getNoteType()==1L){
                // 文件夹：递归收集子孙，按各自 noteType 物理清理关联数据，合并进批量物理删除
                List<NoteNote> descendants = collectDescendantNotes(noteId);
                for (NoteNote child : descendants) {
                    Long childId = child.getId();
                    if (child.getNoteType() == 4L) {
                        // type=4 子孙：物理删除多维表格（含 view/record/item(type=21)/column(type=21)/dwtable）
                        List<NoteDwtable> childDwtables = noteDwtableMapper.selectDWTableList(childId);
                        if (childDwtables != null && !childDwtables.isEmpty()) {
                            for (NoteDwtable dwtable : childDwtables) {
                                noteDwtableServiceImpl.deleteNoteDwtableById(dwtable.getId());
                            }
                        }
                    } else if (child.getNoteType() == 2L) {
                        // type=2 子孙：NoteMeta.deleteFlag=2
                        NoteMeta childMeta = noteMetaMapper.selectNoteMetaByNoteId(childId);
                        if (childMeta != null) {
                            childMeta.setDeleteFlag(2L);
                            noteMetaMapper.updateNoteMeta(childMeta);
                        }
                    }
                    // type=1/3/5 子孙：与现有方法体一致，不做额外关联处理
                    allIds.add(childId.toString());
                }
            }
        }
        return noteNoteMapper.deleteNoteFromGarbageByIds(allIds.toArray(new String[0]));
    }

    /**
     * 取消收藏笔记
     *
     * @param id 需要删除的笔记主键
     * @return 结果
     */
    @Override
    public int cancelCollectionByIds(String id) {
        return noteNoteMapper.cancelCollectionById(id);
    }

    /**
     * 收藏笔记
     *
     * @param id 需要删除的笔记主键
     * @return 结果
     */
    @Override
    public int collectionNoteByIds(String id) {
        return noteNoteMapper.collectionNoteById(id);
    }


    /**
     * 设为模板
     *
     * @param id 需要设为模板的笔记主键
     * @return 结果
     */
    @Override
    public int setTemplate(String id) {
        return noteNoteMapper.setTemplate(id);
    }

    /**
     * 撤销模板
     *
     * @param id 需要撤销的笔记主键
     * @return 结果
     */
    @Override
    public int removeTemplate(String id) {
        return noteNoteMapper.removeTemplate(id);
    }

    /**
     * 恢复回收站中笔记
     *
     * @param ids 需要删除的笔记主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverNoteNoteByIds(String[] ids) {

        List<String> allIds = new ArrayList<>(Arrays.asList(ids));
        for(String id: ids){
            Long noteId = Long.parseLong(id);
            NoteNote note= noteNoteMapper.selectNoteNoteById(noteId);
            if(note.getNoteType()==2L){
                //修改笔记的同时去笔记元数据里修改对应信息
                NoteMeta noteMeta = noteMetaMapper.selectNoteMetaByNoteId(noteId);
                noteMeta.setDeleteFlag(0L);
                noteMetaMapper.updateNoteMeta(noteMeta);
            }else if(note.getNoteType()==1L){
                // 文件夹：递归收集子孙，按各自 noteType 恢复关联数据，合并进批量恢复
                List<NoteNote> descendants = collectDescendantNotes(noteId);
                for (NoteNote child : descendants) {
                    Long childId = child.getId();
                    // type=2 子孙：同步 NoteMeta.deleteFlag=0（与现有 type=2 分支一致，已知 NPE 风险保持现状）
                    if (child.getNoteType() == 2L) {
                        NoteMeta childMeta = noteMetaMapper.selectNoteMetaByNoteId(childId);
                        childMeta.setDeleteFlag(0L);
                        noteMetaMapper.updateNoteMeta(childMeta);
                    }
                    // 对所有子孙软恢复 dwtable（与现有循环末尾对所有类型调用 recoverNoteDwtableByNoteId 一致）
                    noteDwtableMapper.recoverNoteDwtableByNoteId(childId);
                    allIds.add(childId.toString());
                }
            }
            //恢复的同时要保证笔记下面的数据表也一起恢复
            noteDwtableMapper.recoverNoteDwtableByNoteId(noteId);
        }
        return noteNoteMapper.recoverNoteNoteByIds(allIds.toArray(new String[0]));
    }

    /**
     * 删除笔记信息
     * 
     * @param id 笔记主键
     * @return 结果
     */
    @Override
    public int deleteNoteNoteById(Long id)
    {
        NoteNote note= noteNoteMapper.selectNoteNoteById(id);
        if(note.getNoteType()==2L){
            //修改笔记的同时去笔记元数据里修改对应信息
            NoteMeta noteMeta = noteMetaMapper.selectNoteMetaByNoteId(id);
            noteMeta.setDeleteFlag(2L);
            noteMetaMapper.updateNoteMeta(noteMeta);
        }
        return noteNoteMapper.deleteNoteNoteById(id);
    }

    @Override
    public List<NoteNote> searchNoteList(NoteNoteVo noteNoteVo, Long userId) {
        List<NoteNote> noteList = null;
        // 管理员显示所有菜单信息
        if (SysUser.isAdmin(userId))
        {
            noteList = noteNoteMapper.selectAllMenuList();
        }
        else
        {
            //角色赋权的笔记
            noteNoteVo.getParams().put("userId", userId);
            noteList = noteNoteMapper.selectNoteListByUserId(userId);
            //用户本身是作者的笔记
            List<NoteNote> authList = noteNoteMapper.selectNoteListByAuthId(userId) ;
            noteList.addAll(authList);
        }
        return noteList;
    }



}
