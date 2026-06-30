package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.NoteDwtableItem;
import com.ruoyi.system.mapper.NoteDwtableItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * NoteDwtableItemServiceImpl单元测试
 * 重点验证：updateNoteDwtableItem在id为null时不触发DB操作，避免生成无效SQL
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class NoteDwtableItemServiceImplTest
{
    @Mock
    private NoteDwtableItemMapper noteDwtableItemMapper;

    @InjectMocks
    private NoteDwtableItemServiceImpl noteDwtableItemService;

    /**
     * 场景1：传入id为null的NoteDwtableItem时，应返回0且不调用mapper
     * 修复前：直接调用mapper，生成"update note_dwtable_item where id = null"导致SQL语法错误
     * 修复后：service层校验id为null，直接返回0
     */
    @Test
    void testUpdateNoteDwtableItem_nullId_returnsZeroWithoutDbCall()
    {
        NoteDwtableItem item = new NoteDwtableItem();
        // id为null，其他字段也全为null

        int result = noteDwtableItemService.updateNoteDwtableItem(item);

        assertEquals(0, result, "id为null时应返回0");
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }

    /**
     * 场景2：传入null对象时，应返回0且不调用mapper
     */
    @Test
    void testUpdateNoteDwtableItem_nullParam_returnsZeroWithoutDbCall()
    {
        int result = noteDwtableItemService.updateNoteDwtableItem(null);

        assertEquals(0, result, "传入null时应返回0");
        verify(noteDwtableItemMapper, never()).updateNoteDwtableItem(any(NoteDwtableItem.class));
    }

    /**
     * 场景3：传入id不为null的有效对象时，应正常调用mapper
     */
    @Test
    void testUpdateNoteDwtableItem_validId_callsMapper()
    {
        NoteDwtableItem item = new NoteDwtableItem();
        item.setId(1L);
        item.setValue("test");
        when(noteDwtableItemMapper.updateNoteDwtableItem(item)).thenReturn(1);

        int result = noteDwtableItemService.updateNoteDwtableItem(item);

        assertEquals(1, result, "有效id应正常更新");
        verify(noteDwtableItemMapper, times(1)).updateNoteDwtableItem(item);
    }
}
