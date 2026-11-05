package com.ruoyi.system.service.impl;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.NoteColumn;
import com.ruoyi.system.domain.NoteDwtable;
import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.domain.NoteNote;
import com.ruoyi.system.domain.NoteRecord;
import com.ruoyi.system.domain.NoteView;
import com.ruoyi.system.domain.SysUploadRecord;
import com.ruoyi.system.domain.vo.NoteRecordVo;
import com.ruoyi.system.mapper.NoteColumnMapper;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import com.ruoyi.system.mapper.NoteDwtableMapper;
import com.ruoyi.system.mapper.NoteNoteMapper;
import com.ruoyi.system.mapper.NoteRecordMapper;
import com.ruoyi.system.mapper.NoteViewMapper;
import com.ruoyi.system.mapper.SysUploadRecordMapper;
import com.ruoyi.system.service.IPersonalExportService;

/**
 * VIP 个人数据导出实现（U9/R14）。
 * <p>
 * 数据归集链：notes（auth=userId，非垃圾）→ dwtables（按 noteId）→
 * columns/views（按 dwtableId）→ records（按 viewId）→ items（按 recordId）；
 * 文件按用户 sys_upload_record 引用收集。全部筛选以属主为界——
 * 用户 A 的导出包不含用户 B 的任何数据（AE-P3）。
 *
 * @author ruoyi
 * @date 2026-11-04
 */
@Service
public class PersonalExportServiceImpl implements IPersonalExportService
{
    private static final Logger log = LoggerFactory.getLogger(PersonalExportServiceImpl.class);

    /** zip 内文件拷贝缓冲区 */
    private static final int COPY_BUFFER = 8192;

    @Autowired
    private NoteNoteMapper noteNoteMapper;

    @Autowired
    private NoteDwtableMapper noteDwtableMapper;

    @Autowired
    private NoteColumnMapper noteColumnMapper;

    @Autowired
    private NoteViewMapper noteViewMapper;

    @Autowired
    private NoteRecordMapper noteRecordMapper;

    @Autowired
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @Autowired
    private SysUploadRecordMapper sysUploadRecordMapper;

    @Override
    public void writeZipTo(Long userId, OutputStream out) throws Exception
    {
        // ===== 数据归集（全部以属主 userId 为界）=====
        List<NoteNote> notes = noteNoteMapper.selectNoteListByAuthId(userId);
        List<NoteDwtable> dwtables = new ArrayList<>();
        List<NoteColumn> columns = new ArrayList<>();
        List<NoteView> views = new ArrayList<>();
        List<NoteRecord> records = new ArrayList<>();
        List<NoteDwtableItem> items = new ArrayList<>();
        for (NoteNote note : notes)
        {
            // 数据表（含多维表格笔记 noteType=4 的归属表）
            List<NoteDwtable> noteTables = noteDwtableMapper.selectDWTableList(note.getId());
            dwtables.addAll(noteTables);
            for (NoteDwtable dwtable : noteTables)
            {
                // 列
                NoteColumn columnQuery = new NoteColumn();
                columnQuery.setDwtableId(dwtable.getId());
                columns.addAll(noteColumnMapper.selectNoteColumnList(columnQuery));
                // 视图
                views.addAll(noteViewMapper.selectNoteViewListByDwtableId(dwtable.getId()));
            }
        }
        // 记录（按视图归属表）
        for (NoteView view : views)
        {
            NoteRecordVo recordQuery = new NoteRecordVo();
            recordQuery.setViewId(view.getId());
            List<NoteRecord> viewRecords = noteRecordMapper.selectNoteRecordList(recordQuery);
            records.addAll(viewRecords);
            // 单元格（按记录）
            for (NoteRecord record : viewRecords)
            {
                NoteDwtableItem itemQuery = new NoteDwtableItem();
                itemQuery.setRecordId(record.getId());
                items.addAll(noteDwtableItemMapper.selectNoteDwtableItemList(itemQuery));
            }
        }
        // 上传记录（文件清单）
        List<SysUploadRecord> uploadRecords = sysUploadRecordMapper.selectSysUploadRecordByUserId(userId);

        // ===== zip 流式写出 =====
        try (ZipOutputStream zip = new ZipOutputStream(out))
        {
            // manifest
            JSONObject manifest = new JSONObject(new LinkedHashMap<>());
            manifest.put("exportTime", new Date().toString());
            manifest.put("userId", userId);
            manifest.put("noteCount", notes.size());
            manifest.put("dwtableCount", dwtables.size());
            manifest.put("columnCount", columns.size());
            manifest.put("viewCount", views.size());
            manifest.put("recordCount", records.size());
            manifest.put("itemCount", items.size());
            manifest.put("fileCount", uploadRecords.size());
            writeJsonEntry(zip, "manifest.json", manifest);

            writeJsonEntry(zip, "notes.json", notes);
            writeJsonEntry(zip, "dwtables.json", dwtables);
            writeJsonEntry(zip, "columns.json", columns);
            writeJsonEntry(zip, "views.json", views);
            writeJsonEntry(zip, "records.json", records);
            writeJsonEntry(zip, "items.json", items);
            writeJsonEntry(zip, "upload-records.json", uploadRecords);

            // 用户文件（按上传记录引用，路径 /profile/** → profile 目录）
            int copied = 0;
            for (SysUploadRecord record : uploadRecords)
            {
                String filePath = record.getFilePath();
                if (StringUtils.isEmpty(filePath) || !filePath.startsWith(Constants.RESOURCE_PREFIX))
                {
                    continue;
                }
                File file = new File(RuoYiConfig.getProfile()
                        + filePath.substring(Constants.RESOURCE_PREFIX.length()));
                if (!file.exists())
                {
                    continue;
                }
                // zip 内路径去 /profile 前缀
                zip.putNextEntry(new ZipEntry("files" + filePath.substring(Constants.RESOURCE_PREFIX.length())));
                try (InputStream in = Files.newInputStream(Paths.get(file.getAbsolutePath())))
                {
                    byte[] buffer = new byte[COPY_BUFFER];
                    int len;
                    while ((len = in.read(buffer)) > 0)
                    {
                        zip.write(buffer, 0, len);
                    }
                }
                zip.closeEntry();
                copied++;
            }
            log.info("VIP个人导出: userId={}, 笔记={}, 表格={}, 文件={}", userId, notes.size(), dwtables.size(), copied);
        }
    }

    private void writeJsonEntry(ZipOutputStream zip, String entryName, Object data) throws Exception
    {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(JSON.toJSONString(data).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
