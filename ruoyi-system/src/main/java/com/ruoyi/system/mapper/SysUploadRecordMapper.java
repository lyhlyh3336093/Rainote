package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.SysUploadRecord;

/**
 * 用户上传容量记录Mapper接口
 *
 * @author ruoyi
 * @date 2026-11-04
 */
public interface SysUploadRecordMapper
{
    /**
     * 新增上传记录（上传成功后写入，计入容量）
     */
    public int insertSysUploadRecord(SysUploadRecord record);

    /**
     * 统计用户已用总容量（字节）
     */
    public Long sumFileSizeByUserId(Long userId);

    /**
     * 查询用户全部上传记录（个人导出/对账）
     */
    public List<SysUploadRecord> selectSysUploadRecordByUserId(Long userId);

    /**
     * 按文件路径删除记录（回收站清理释放容量）
     */
    public int deleteSysUploadRecordByFilePaths(@Param("filePaths") List<String> filePaths);
}
