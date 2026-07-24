import { message } from 'ant-design-vue';

export interface JumpToTableCellPayload {
  noteId: string | number;
  linkDwTableId: string | number;
  linkColumnId: string | number;
  linkItemId: string | number;
  linkRecordId?: string | number;
}

/** 定位参数子集（不含 noteId），供 onSemanticLinkLocate 等消费方复用 */
export type LocatePayload = Omit<JumpToTableCellPayload, 'noteId'>;

/**
 * 跳转到多维表格单元格：新标签页打开目标表格视图，携带定位参数。
 * 复用 toNote 的 window.open 范式；定位参数由 baseTable 挂载后消费。
 */
export const jumpToTableCell = (payload: JumpToTableCellPayload): void => {
  const { noteId, linkDwTableId, linkColumnId, linkItemId, linkRecordId } = payload || {};
  if (!noteId || !linkDwTableId || !linkColumnId || !linkItemId) {
    message.warning('该引用缺少单元格定位信息');
    return;
  }
  const params = new URLSearchParams({
    locateColumnId: String(linkColumnId),
    locateItemId: String(linkItemId),
  });
  if (linkRecordId != null) {
    params.set('locateRecordId', String(linkRecordId));
  }
  const safeNoteId = encodeURIComponent(String(noteId));
  const safeTableId = encodeURIComponent(String(linkDwTableId));
  const url = `/base/${safeNoteId}/${safeTableId}?${params.toString()}`;
  const win = window.open(url, '_blank', 'noopener,noreferrer');
  if (!win) {
    message.warning('新标签页被浏览器拦截，请允许弹窗后重试');
  }
};
