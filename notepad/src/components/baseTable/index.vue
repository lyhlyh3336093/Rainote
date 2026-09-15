<template>
  <div>
    <Toolbar @click="(item, key) => onToolbarClick(item, key)">
      <a-button @click="table.delete" type="primary" danger class="m-2.5">
        <delete-outlined />
        删除记录
      </a-button>
      <a-tooltip v-if="exportDisabled" title="请先选择多维表格" placement="bottom">
        <a-button class="m-2.5" disabled>
          <upload-outlined />
          导入
        </a-button>
      </a-tooltip>
      <a-button
        v-else
        class="m-2.5"
        :loading="importState.loading"
        :disabled="importState.loading"
        @click="importState.pick"
      >
        <upload-outlined />
        导入
      </a-button>
      <input
        ref="importFileInput"
        type="file"
        accept=".sql,.zip,.xlsx"
        style="display: none"
        @change="importState.onFileChosen"
      />
      <a-tooltip v-if="exportDisabled" title="请先选择多维表格" placement="bottom">
        <a-button class="m-2.5" disabled>
          <download-outlined />
          导出
        </a-button>
      </a-tooltip>
      <a-button
        v-else
        class="m-2.5"
        :loading="exportModal.loading"
        :disabled="exportModal.loading"
        @click="exportModal.open"
      >
        <download-outlined />
        导出
      </a-button>
    </Toolbar>
    <vxe-table ref="xTable" :column-config="{ resizable: true, minWidth: 150 }" :data="table1.tableData" :edit-config="{
      trigger: 'dblclick',
      mode: 'cell',
      showStatus: true,
      showIcon: false,
    }" :mouse-config="{ selected: true }" :cell-config="rowConfig" :tree-config="treeConfig" :height="height"
      keep-source show-overflow @edit-closed="table.edit" :loading="table1.loading" border auto-resize
      :scroll-x="{ enabled: true, gt: 0 }">
      <vxe-column type="checkbox" width="50" align="center">
        <template #header="{ checked, indeterminate }">
          <a-checkbox :checked="checked" :indeterminate="indeterminate" @change="xTable.toggleAllCheckboxRow()" />
        </template>
        <template #checkbox="{ row, checked, indeterminate }">
          <a-checkbox :checked="checked" :indeterminate="indeterminate" @change.stop="xTable.toggleCheckboxRow(row)" />
        </template>
      </vxe-column>

      <template v-for="(column, index) in table1.columnsId" :key="column">
        <vxe-column :edit-render="{}" :field="`${column}`" show-header-overflow show-overflow="title"
          :title="table1.columnsName[index]" :tree-node="index === 0 ? true : false">
          <template #edit="{ row }">
            <component v-if="fields[table1.columnsType[index]].value" :is="fields[table1.columnsType[index]].value"
              :forwardRef="setRef" v-model:value="row[column]" :props="{
                ...fields[table1.columnsType[index]].props,
                columnId: column,
                record: row,
                list: table1.list,
                datasheetID,
                viewId
              }" :type="table1.columnsType[index]" @click="
                table.cell.click(
                  table1.columnsType[index],
                  columns[index].property,
                  { index, id: row.id, column },
                  row[column]
                )
                " @tag-click="table.tagClick"></component>
          </template>
          <template #default="{ row }">
            <template
              v-if="[FieldEnum.多选, FieldEnum.双向关联, FieldEnum.集合运算, FieldEnum.lookUp].includes(table1.columnsType[index])">
              <template v-if="row[column]">
                <template v-for="(item, z) in getTags(row[column])" :key="z">
                  <a-tag>{{ item?.label || item }}</a-tag>
                </template>
                <!-- <a-tag v-if="getTags(row[column]).length > 3">
                  +{{ getTags(row[column]).length - 3 }}
                </a-tag> -->
              </template>
            </template>
            <template v-else-if="table1.columnsType[index] === FieldEnum.语义关联">
              <span class="semantic-word-list">
                <template v-if="getSemanticCellState(row, column) === 'loading'">
                  <a-spin size="small" /> <span class="semantic-word-loading">加载中…</span>
                </template>
                <template v-else-if="getSemanticCellState(row, column) === 'empty'">
                  <span class="semantic-word-empty">—</span>
                </template>
                <template v-else-if="getSemanticCellState(row, column) === 'error'">
                  <a-tooltip title="加载失败,点击重试">
                    <span class="semantic-word-error" @click="retrySemanticCell(row.id, column)">!</span>
                  </a-tooltip>
                </template>
                <template v-else>
                  <template v-for="(word, wIdx) in getSemanticWords(row, column, index)" :key="wIdx">
                    <span v-if="wIdx > 0" class="semantic-word-sep">, </span>
                    <button type="button" class="semantic-word" :data-link-id="word.linkId || ''"
                      @click="onSemanticWordClick(word, row, column, index)">{{ word.text }}</button>
                  </template>
                </template>
              </span>
              <a-button type="link" size="small" @click="openNotePicker(row.id, column)">关联笔记</a-button>
            </template>
            <template v-else-if="table1.columnsType[index] === FieldEnum.链接多行文本">
              <template v-for="(part, index) in textLink.parseContent(row[column])" :key="index">
                <span v-if="part.type === 'text'">{{ part.content }}</span>
                <a-button v-else type="link" size="small" @click="textLink.handleLinkClick(part.tableId)"
                  class="link-button">
                  [{{ part.text }}]
                </a-button>
              </template>
            </template>
            <template v-else>{{ row[column] }}</template>
          </template>
          <template #header="{ column: column1 }">
            <form-outlined @click="edit.click(column1)" class="cursor-pointer" />
            {{ column1["title"] }}
          </template>
        </vxe-column>
      </template>
    </vxe-table>
    <!-- <s-table
        :columns="table1.columnsId"
        :data-source="table1.tableData"
        :pagination="false"
        :scroll="{ y: 500, x: 2000 }"
      >
        <template #bodyCell="{ column }">
          <template v-if="column.key === 'operation'">
            <a>Action</a>
          </template>
        </template>
      </s-table> -->
    <a-modal width="70%" okText="确定" cancelText="取消" :title="`已关联 ${table.state.related.name}`"
      v-model:open="table.state.visible" destroyOnClose @ok="table.cell.related" @cancel="table.onPanelCancel"
      :ok-button-props="{ disabled: [FieldEnum.集合运算, FieldEnum.lookUp].includes(table.state.related.type) }"
      :cancel-button-props="{ disabled: [FieldEnum.集合运算, FieldEnum.lookUp].includes(table.state.related.type) }">
      <Related ref="relatedRef" :data="table.state.related" />
    </a-modal>
    <a-modal width="70%" :footer="null" :title="`来自 ${table.state.form.name}`" v-model:open="table.state.formVisible"
      destroyOnClose>
      <Form ref="formRef" :data="table.state.form" />
    </a-modal>
    <a-modal @cancel="edit.cancel" v-model:open="edit.visible" :footer="null" centered
      :title="edit.current ? '编辑字段' : '新增字段'" destroyOnClose>
      <Edit @visible="edit.cancel" :options="edit.options" :formState="edit.formState" :edit="edit.current" />
    </a-modal>
    <a-modal width="60%" @cancel="note.cancel" v-model:open="note.visible" :footer="null" centered title="语义关联"
      destroyOnClose>
      <div style="height:50vh;overflow:auto">
        <Editor @save="note.save" :id="note.id" :tableId="note.tableId" :recordId="note.recordId"
          :linkColumnId="note.linkColumnId" :linkItemId="note.linkItemId" :linkId="note.linkId" :tableNoteId="note.tableNoteId" />
      </div>
    </a-modal>

    <!-- 反向关联:选笔记 picker -->
    <a-modal v-model:open="notePicker.visible" title="选择笔记" width="500px" :footer="null" destroyOnClose centered>
      <a-select show-search allow-clear v-model:value="notePicker.selectedNoteId" placeholder="搜索笔记标题"
        style="width: 100%" :filter-option="false" :loading="notePicker.loading"
        :options="notePicker.list.map((n: any) => ({ label: n.title, value: n.id }))"
        @search="notePicker.search" />
      <div style="text-align: right; margin-top: 16px">
        <a-button style="margin-right: 8px" @click="notePicker.cancel">取消</a-button>
        <a-button type="primary" :disabled="!notePicker.selectedNoteId" @click="notePicker.confirm">确定</a-button>
      </div>
    </a-modal>

    <!-- 多维表格导出格式选择 -->
    <a-modal
      v-model:open="exportModal.visible"
      title="导出多维表格"
      width="420px"
      :footer="null"
      destroyOnClose
      centered
    >
      <a-radio-group v-model:value="exportModal.format" style="width: 100%">
        <a-radio value="excel" style="display: block; margin-bottom: 8px">Excel（.xlsx，适合查看与分享）</a-radio>
        <a-radio value="sql" style="display: block">SQL（.sql，适合数据迁移与备份）</a-radio>
      </a-radio-group>
      <div style="text-align: right; margin-top: 16px">
        <a-button style="margin-right: 8px" :disabled="exportModal.loading" @click="exportModal.cancel">取消</a-button>
        <a-button
          type="primary"
          :disabled="!exportModal.format"
          :loading="exportModal.loading"
          @click="exportModal.confirm"
        >
          确认导出
        </a-button>
      </div>
    </a-modal>

    <!-- Excel 导入预检弹框（补参模式/只读确认模式，U6） -->
    <ImportPrecheckModal
      v-if="excelImport.precheck"
      v-model:open="excelImport.modalOpen"
      :precheck="excelImport.precheck"
      :file="excelImport.file"
      :noteId="excelImport.noteId"
      :dwtableId="excelImport.dwtableId"
      @success="excelImport.onImportSuccess"
    />

    <!-- 多行文本选词关联弹出框 -->
    <a-modal v-model:open="textLink.visible" title="文本关联" width="800px" @ok="textLink.confirmLink" :footer="null">
      <div class="text-link-content-wrapper">
        <!-- 文本内容区域 -->
        <div class="text-content-area" @mouseup="textLink.handleTextSelection">
          <div class="content-text">
            <template v-for="(part, index) in textLink.parseContent(textLink.content)" :key="index">
              <span v-if="part.type === 'text'">{{ part.content }}</span>
              <a-button v-else type="link" size="small" @click="textLink.handleLinkClick(part.tableId)"
                class="link-button">
                [{{ part.text }}]
              </a-button>
            </template>
          </div>
        </div>

        <!-- 选词操作区域 -->
        <div v-if="textLink.selectedText" class="selection-actions">
          <a-divider>已选中: {{ textLink.selectedText }}</a-divider>
          <div class="action-buttons">
            <span class="action-label">关联到数据表:</span>
            <a-select allowClear v-model:value="textLink.selectedTableId" placeholder="选择数据表"
              style="width: 200px; margin-right: 8px" :options="datasheet?.state?.list?.filter(item => `${item.id}` !== `${textLink.tableId}`).map((item: any) => ({
                label: item.name,
                value: item.id
              })) || []" />
            <a-button type="primary" :disabled="!textLink.selectedTableId" @click="textLink.confirmLink">
              确认关联
            </a-button>
          </div>
        </div>

        <!-- 已有关联列表 -->
        <div v-if="textLink.existingLinks && textLink.existingLinks.length > 0" class="existing-links">
          <a-divider>已有关联</a-divider>
          <a-space wrap>
            <a-tag v-for="(link, index) in textLink.existingLinks" :key="index" closable
              @close="textLink.removeLink(index)">
              {{ link.text }} → {{ link.tableName }}
            </a-tag>
          </a-space>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script lang="tsx">
import { defineComponent, reactive, ref, provide, onMounted, inject, Ref, onUnmounted, nextTick, watch, computed } from 'vue';
import type { VxeTableInstance } from 'vxe-table'
import Toolbar from './toolbar/index.vue';
import { message, Modal } from 'ant-design-vue';
import { storeToRefs } from 'pinia';
import { useRoute, useRouter } from 'vue-router';
import { useStore } from '../../stores/table';
import { useFetch, useSortable } from '../../hooks';
import { exportDwtable, type ExportFormat } from '@/api/export';
import { importDwtable, precheckExcelImport, importExcelData } from '@/api/import';
import type { ExcelImportPrecheckResult, ExcelImportParamsPayload } from '@/api/import';
import Related from './related/index.vue';
import Form from './form/index.vue';
import { clone } from 'xe-utils';
import Edit from './field/edit.vue';
import ImportPrecheckModal from './ImportPrecheckModal.vue';
import { FieldEnum } from "@/enum";
import { safeParseJson, Bus } from "@/utils";
import type { LocatePayload } from "@/utils";
import Editor from '../../views/doc/index.vue';
export interface TableData {
  type?: string;
  name?: string;
  children?: [];
  id?: string | number;
  date?: string | number;
  size?: string | number | null;
  parentId?: string | number | null;
}

export default defineComponent({
  name: "np-base-table",
  props: ['height'],
  components: { Toolbar, Related, Form, Edit, Editor, ImportPrecheckModal },
  setup(props, ctx) {
    const store: any = useStore();
    const route = useRoute();
    const router = useRouter();
    const { viewId } = ctx.attrs;
    const relatedRef = ref();
    const datasheet: Ref<any> = ref(inject("datasheet"));
    const { id, datasheetID, columns, items, rowHeight, fields, table: table1 } = storeToRefs(store);
    const xTable = ref<VxeTableInstance>();
    const rowConfig = reactive({
      height: rowHeight.value.active,
    });
    const treeConfig = ref({
      transform: true,
      rowField: 'id',
      parentField: 'parentId'
    });
    // 表格视图相关
    const table = {
      state: reactive({
        list: [],
        related: {},
        visible: false,
        form: {},
        formVisible: false,
      }) as any,
      get: {
        columns() {
          store.getColumns(datasheetID.value);
        },
        list() {
          store.getTableList();
        },
      },
      async edit(props) {
        const { row, column, $columnIndex } = props;
        const field = column.field || column.type;
        let cellValue = row[field];
        const columnId = column.field;
        const _type = table1.value.columnsType[$columnIndex - 1];

        // 集合运算或 lookup 类型的列不可在此处编辑更新数据，跟随源数据变化
        if ([FieldEnum.集合运算, FieldEnum.lookUp].includes(_type)) {
          return;
        }

        if (cellValue !== column.model.value) {
          const id = table1.value.list.find(v => v.id === row.id)['itemId'][columnId];
          let recordId = '';
          if (_type === FieldEnum.双向关联) {
            recordId = cellValue?.map(item => item.value).toString();
            cellValue = cellValue?.map(item => item.label).toString();
          } else {
            cellValue = cellValue?.toString();
          }

          let items: any = [
            {
              id,
              name: column.title,
              value: cellValue,
              dwtId: datasheetID.value,
              columnId
            }
          ];
          if (_type === FieldEnum.双向关联) {
            const oldRecordId = table1.value.list.find(v => v.id === row.id)['linkRecordId'][columnId];
            const oldValue = column.model.value?.toString();
            items[0] = {
              ...items[0],
              oldRecordId,
              oldValue,
              recordId,
              columnId,
            }
          }
          table.update({
            items,
            viewId,
            id: row.id,
          });
        } else {
          // message.info(`局部未更新，不需要保存！`);
        }
      },
      async delete() {
        const checkboxRecords = xTable.value.getCheckboxRecords();
        if (checkboxRecords.length === 0) {
          return message.warning('请选择需要删除的数据');
        }
        const { data } = await useFetch(`/system/record/remove/${checkboxRecords.map(item => item.id).toString()}`).get().json();
        if (data?.value) {
          table.get.list();
          message.success('删除成功');
        }
      },
      cell: {
        click(type, props, cell, content) {
          // 解析属性
          props = safeParseJson(props);

          // 根据类型获取目标 ID
          let targetId: string | undefined;
          if (type === FieldEnum.集合运算) {
            // 集合运算：从 columnAId 找到对应列，再取其 property.table_id
            const column = columns.value.find((item: any) => item.id === props.columnAId);
            const property = safeParseJson(column?.property);
            targetId = property?.table_id;
          } else if (type === FieldEnum.lookUp) {
            // lookUp：从 double_link_column_id 找到对应列，再取其 property.table_id
            const column = columns.value.find((item: any) => item.id === props.double_link_column_id);
            const property = safeParseJson(column?.property);
            targetId = property?.table_id;
          } else if (type === FieldEnum.双向关联) {
            // 双向关联：取 props.table_id
            targetId = props?.table_id;
          } else if (type === FieldEnum.语义关联) {
            targetId = props?.note_id;
            const noteId = props?.note_id;
            note.visible = true;
            note.id = noteId;
            note.tableId = datasheetID.value
            note.recordId = cell.id;
            // 正向查看:清空反向上下文,避免上次反向残留误判为反向模式
            note.linkColumnId = null;
            note.linkItemId = null;
            // 正向查看(非词级跳转):不携带 data-link-id
            note.linkId = null;
            // 当前多维表格归属笔记 id — 用于弹框内 forward 锚点点击跳转
            note.tableNoteId = getCurrentTableNoteId();
            return;
          } else if (type === FieldEnum.链接多行文本) {
            textLink.visible = true;
            textLink.columnId = cell.column;
            textLink.tableId = datasheetID.value;
            textLink.recordId = cell.id;
            textLink.content = content || '';
            textLink.selectedText = '';
            // 清空并重新解析已有关联列表
            textLink.existingLinks = [];
            // 使用 nextTick 确保在下一帧执行，此时 content 已经更新
            nextTick(() => {
              textLink.rebuildExistingLinks();
            });
            return;
          }

          // lookUp关联的表格
          const related = datasheet.value.state.all.find((item: any) => `${item.id}` === `${targetId}`);
          if (!related) return;

          // lookUp当前记录
          const record = table1.value.list.find((item: any) => `${item.id}` === `${cell.id}`);
          if (!record) return;

          // 设置关联状态
          // 已选集合解析键：双向链接列直接用 cell.column；lookup 列(type=26) item 不存储
          // linkRecordId（恒为 NULL），需经其引用的双向链接列 double_link_column_id 取键，
          // 与后端 getLookupLinkRecordIds 解析路径一致。
          const linkRecordKey = type === FieldEnum.lookUp ? props.double_link_column_id : cell.column;
          table.state.related = {
            type,
            ...related,
            id: targetId,
            cell,
            record: record['linkRecordId']?.[linkRecordKey]?.split(',') || []
          };
          // [PinPanel] 打开节点：记录列类型、解析键、已选集合、目标表
          console.log('[PinPanel] open cell.click', {
            type,
            cellId: cell.id,
            cellColumn: cell.column,
            linkRecordKey,
            record: table.state.related.record,
            targetId,
            relatedName: related?.name,
          });
          table.state.visible = true;
        },
        related() {
          const rows = relatedRef.value.xTable.getCheckboxRecords();
          // [PinPanel] OK 保存节点：记录当前勾选行
          console.log('[PinPanel] OK save related()', {
            selectedCount: rows.length,
            selectedIds: rows.map(r => r.id),
            panelType: table.state.related.type,
          });
          if (rows.length === 0) {
            console.warn('[PinPanel] OK save aborted: no rows selected, panel stays open');
            return message.warning('请选择需要关联的数据');
          }
          const cell = columns.value[table.state.related.cell.index];
          const row = table1.value.list.find(item => item.id === table.state.related.cell.id);
          const oldRecordId = row.linkRecordId[cell.id] || '';
          const oldValue = row.tableData[cell.id]?.toString() || '';
          const oldRecords = row?.records?.[cell.id] || [];

          const ids = rows.map(v => (v.id));
          const records = relatedRef.value.table.state.list.filter(item => ids.includes(item.id)).map(item => ({
            columnId: Object.keys(item.itemId),
            itemId: Object.values(item.itemId)
          }));
          cell.recordId = ids.toString();
          cell.value = rows.map((v, i) => Object.values(v)[0]).filter(Boolean).toString();
          let { name, value, recordId, id } = cell;
          if (!row['itemId'][id]) {
            // [PinPanel] OK 保存静默中止：缺少 itemId，面板保持打开（潜在数据不一致风险）
            console.warn('[PinPanel] OK save aborted: row.itemId[cellId] missing, panel stays open', {
              rowId: table.state.related.cell.id,
              cellId: id,
            });
            return;
          }
          const items = [
            {
              name,
              value,
              // records,
              recordId,
              oldValue,
              // oldRecords,
              oldRecordId,
              columnId: id,
              id: row['itemId'][id],
              dwtId: datasheetID.value
            }
          ];
          table.update({
            items,
            viewId,
            id: table.state.related.cell.id,
          })
        }
      },
      async update(params) {
        const { data } = await useFetch('/system/record/update').post(params).json();
        if (data?.value) {
          // [PinPanel] 关闭节点-OK保存成功：刷新列表并关闭面板
          console.log('[PinPanel] update success, closing panel', { cellId: params.id });
          table.get.list();
          table.state.visible = false;
        } else {
          console.warn('[PinPanel] update failed, panel stays open');
        }
      },
      // [PinPanel] 关闭节点-取消/X/遮罩：a-modal @cancel 触发
      onPanelCancel() {
        console.log('[PinPanel] cancel close (cancel btn / X / mask / ESC)', {
          panelType: table.state.related.type,
          hadUnsavedChanges: false,
        });
      },
      tagClick({ columnId, property, record, value, label }) {
        const { table_id, table_name } = property;
        const data = {
          columnId,
          value,
          recordId: record.id,
          table_id,
          name: table_name,
          fields: clone(fields),
        }
        table.state.form = data;
        table.state.formVisible = true;
      }
    }
    const note = reactive({
      id: null,
      visible: false,
      tableId: null,
      recordId: null,
      // 反向上下文:从单元格发起关联时携带,正向查看时为 null
      linkColumnId: null as string | number | null,
      linkItemId: null as string | number | null,
      // U4: 词级跳转的 data-link-id;反向词为 NoteNotelink.id,正向历史词为空字符串,无跳转为 null
      linkId: null as string | number | null,
      // 当前多维表格的归属笔记 id(NoteDwtable.noteId) — 传给 Editor/SemanticLink,
      // 用于点击锚点跳转 /base/{tableNoteId}/{dwtableId}(语义上 ≠ note.id:弹框打开的笔记 id)
      tableNoteId: null as string | number | null,
      save() { },
      cancel() {
        note.visible = false;
        note.linkId = null;
      },
    })
    // 从 datasheet.state.all 查当前多维表格的归属笔记 id(NoteDwtable.noteId)
    const getCurrentTableNoteId = (): string | number | null => {
      const all = datasheet.value?.state?.all || [];
      const found = all.find((item: any) => `${item.id}` === `${datasheetID.value}`);
      return found?.noteId ?? null;
    }
    // 多维表格导出:当前归属笔记 id(对应后端 exportData?noteId= 参数)
    const tableNoteId = computed(() => getCurrentTableNoteId());
    const exportDisabled = computed(() => !tableNoteId.value);
    const exportModal = reactive({
      visible: false,
      format: null as ExportFormat | null,
      loading: false,
      open() {
        if (!tableNoteId.value) {
          return message.warning('请先选择多维表格');
        }
        exportModal.format = null;
        exportModal.visible = true;
      },
      cancel() {
        if (exportModal.loading) return;
        exportModal.visible = false;
        exportModal.format = null;
      },
      async confirm() {
        if (!exportModal.format) {
          return message.warning('请选择导出格式');
        }
        const noteId = tableNoteId.value;
        if (!noteId) {
          return message.error('未找到当前多维表格归属笔记');
        }
        exportModal.loading = true;
        const hide = message.loading({ content: '正在导出，请稍候…', duration: 0 });
        try {
          await exportDwtable(noteId, exportModal.format);
          hide();
          message.success('导出成功，已开始下载');
          exportModal.visible = false;
          exportModal.format = null;
        } catch (e: any) {
          hide();
          message.error(e?.message || '导出失败，请稍后重试');
        } finally {
          exportModal.loading = false;
        }
      },
    });
    // 多维表格导入:隐藏 file input + 导入状态(R1 accept .sql/.zip/.xlsx,按扩展名分流:
    // .sql/.zip 走既有 SQL 导入路径不动;.xlsx 走两阶段预检→补参→导入,U6)
    const importFileInput = ref<HTMLInputElement | null>(null);
    // Excel 两阶段导入状态:预检结果 + 弹框开关 + 阶段二上下文(弹框组件消费)
    const excelImport = reactive({
      modalOpen: false,
      precheck: null as ExcelImportPrecheckResult | null,
      file: null as File | null,
      noteId: null as string | number | null,
      dwtableId: null as string | number | null,
      // 弹框确认导入成功后:清空预检上下文并刷新表格(由 ImportPrecheckModal @success 触发)
      onImportSuccess() {
        excelImport.modalOpen = false;
        excelImport.precheck = null;
        excelImport.file = null;
        table.get.list();
      },
    });
    // 完全干净分支的直连导入(无弹框,R13):params 只含 fileFingerprint + 空 baseline/selections
    const buildCleanParams = (precheck: ExcelImportPrecheckResult): ExcelImportParamsPayload => ({
      fileFingerprint: precheck.fileFingerprint,
      precheckBaseline: {
        missingColumns: (precheck.missingParams || []).map((m) => m.columnName),
        missNames: [],
        ambiguity: (precheck.ambiguityItems || []).map((a) => ({
          columnName: a.columnName,
          name: a.name,
          preselectedRecordId: a.preselectedRecordId ?? null,
        })),
      },
      columnValues: {},
      relationSelections: [],
    });
    // 是否仅有影响面信息(无缺参无歧义,R13 轻量确认分支的判据)
    const hasImpactInfo = (p: ExcelImportPrecheckResult): boolean =>
      (p.defaultValueFills?.length || 0) > 0
      || (p.newOptions?.length || 0) > 0
      || (p.symmetricWriteImpact?.length || 0) > 0
      || (p.ignoredSheets?.length || 0) > 0
      || (p.skippedHeaders?.length || 0) > 0;
    const importState = reactive({
      loading: false,
      pick() {
        if (!tableNoteId.value) {
          return message.warning('请先选择多维表格');
        }
        importFileInput.value?.click();
      },
      async onFileChosen(e: Event) {
        const input = e.target as HTMLInputElement;
        const file = input.files?.[0];
        // 清空选择,允许重复导入同一文件
        input.value = '';
        if (!file) return;
        const name = file.name.toLowerCase();
        const isExcel = name.endsWith('.xlsx');
        if (!isExcel && !name.endsWith('.sql') && !name.endsWith('.zip')) {
          return message.error('不支持的文件类型，仅支持 .sql / .zip / .xlsx');
        }
        // 与后端 multipart max-file-size(10MB) 对齐的前端预检，避免超限后才收到全局异常
        if (file.size > 10 * 1024 * 1024) {
          return message.error('文件超过 10MB 上传上限，请分批导入');
        }
        const noteId = tableNoteId.value;
        if (!noteId) {
          return message.error('未找到当前多维表格归属笔记');
        }
        if (isExcel) {
          return importState.runExcelImport(noteId, file);
        }
        importState.loading = true;
        const hide = message.loading({ content: '正在导入，请稍候…', duration: 0 });
        try {
          const recordCount = await importDwtable({ noteId, dwtableId: datasheetID.value, file });
          hide();
          Modal.success({ title: '导入成功', content: `成功导入 ${recordCount} 条记录` });
          table.get.list();
        } catch (e: any) {
          hide();
          Modal.error({ title: '导入失败', content: e?.message || '导入失败，请稍后重试' });
        } finally {
          importState.loading = false;
        }
      },
      // .xlsx 两阶段流程:预检 → 三分流(干净直连/补参弹框/轻量确认),R13
      // #11: 预检/导入分阶段 catch,标题按阶段固定(不再依赖异常消息文本判定)
      async runExcelImport(noteId: string | number, file: File) {
        importState.loading = true;
        const hide = message.loading({ content: '正在预检，请稍候…', duration: 0 });
        try {
          let precheck: ExcelImportPrecheckResult;
          // 预检段:任何异常均为"预检失败"
          try {
            precheck = await precheckExcelImport({
              noteId,
              dwtableId: datasheetID.value,
              file,
            });
          } catch (e: any) {
            Modal.error({ title: '预检失败', content: e?.message || '预检失败，请稍后重试' });
            return;
          }
          // 导入段:仅干净分支直连(补参/确认分支的导入调用在弹框组件内)
          try {
            if (!precheck.hasBlockingIssues && !hasImpactInfo(precheck)) {
              // 完全干净:直接导入(params 只含指纹 + 空 baseline/selections,按契约发射)
              const recordCount = await importExcelData(
                { noteId, dwtableId: datasheetID.value, file },
                buildCleanParams(precheck),
              );
              Modal.success({ title: '导入成功', content: `成功导入 ${recordCount} 条记录` });
              table.get.list();
            } else {
              // 补参模式(缺参/歧义)或只读确认模式(仅影响面):一个组件两种模式
              excelImport.precheck = precheck;
              excelImport.file = file;
              excelImport.noteId = noteId;
              excelImport.dwtableId = datasheetID.value;
              excelImport.modalOpen = true;
            }
          } catch (e: any) {
            Modal.error({ title: '导入失败', content: e?.message || '导入失败，请稍后重试' });
          }
        } finally {
          hide();
          importState.loading = false;
        }
      },
    });
    // 反向关联:选笔记 picker
    const notePicker = reactive({
      visible: false,
      loading: false,
      selectedNoteId: null as string | number | null,
      list: [] as any[],
      // 反向上下文:发起关联的列 id、单元格 itemId、源行 id
      linkColumnId: null as string | number | null,
      linkItemId: null as string | number | null,
      recordId: null as string | number | null,
      async search(v: string) {
        notePicker.loading = true;
        try {
          const { data } = await useFetch(`/system/note/list?pageNum=1&pageSize=20${v ? `&title=${v}` : ''}`).get().json();
          notePicker.list = data.value?.data || [];
        } finally {
          notePicker.loading = false;
        }
      },
      open(linkColumnId: string | number, linkItemId: string | number, recordId: string | number) {
        notePicker.linkColumnId = linkColumnId;
        notePicker.linkItemId = linkItemId;
        notePicker.recordId = recordId;
        notePicker.selectedNoteId = null;
        notePicker.visible = true;
        notePicker.search('');
      },
      confirm() {
        if (!notePicker.selectedNoteId) {
          return message.warning('请先选择笔记');
        }
        // 打开笔记 Editor,传入反向上下文
        note.id = notePicker.selectedNoteId;
        note.tableId = datasheetID.value;
        note.recordId = notePicker.recordId;
        note.linkColumnId = notePicker.linkColumnId;
        note.linkItemId = notePicker.linkItemId;
        // 反向创建流程:不进行词级跳转定位
        note.linkId = null;
        // 当前多维表格归属笔记 id — 用于反向锚点点击跳转 /base/{tableNoteId}/{dwtableId}
        note.tableNoteId = getCurrentTableNoteId();
        note.visible = true;
        notePicker.visible = false;
      },
      cancel() {
        notePicker.visible = false;
      },
    });
    // 从 type=25 单元格发起"关联笔记":获取 linkItemId(NoteDwtableItem.id)后打开 picker
    const openNotePicker = (rowId: string | number, columnId: string | number) => {
      const row = table1.value.list.find((v: any) => v.id === rowId);
      if (!row) {
        return message.error('未找到当前记录');
      }
      const linkItemId = row.itemId?.[columnId];
      if (!linkItemId) {
        return message.error('当前单元格未初始化,请先编辑该单元格');
      }
      notePicker.open(columnId, linkItemId, rowId);
    };
    // === U4: 语义关联列(type=25)双源合并渲染 + 词级跳转 ===
    // 按 `rowId-colId` 维护每个 type=25 单元格的异步聚合查询结果(NoteNotelink 列表)
    const semanticCellData = reactive({} as Record<string, { loading: boolean; reverseWords: any[]; error: any }>);
    // 异步查询某单元格的 NoteNotelink 聚合记录
    const loadSemanticCell = async (rowId: string | number, colId: string | number, force = false) => {
      const key = `${rowId}-${colId}`;
      if (!force && semanticCellData[key]) return;
      semanticCellData[key] = { loading: true, reverseWords: [], error: null };
      try {
        const rowItem = table1.value.list.find((v: any) => v.id === rowId);
        const linkItemId = rowItem?.itemId?.[colId];
        if (!linkItemId) {
          semanticCellData[key] = { loading: false, reverseWords: [], error: null };
          return;
        }
        const { data } = await useFetch(`system/notelink/cell/${colId}/${linkItemId}`).get().json();
        const list = data.value?.data || [];
        semanticCellData[key] = { loading: false, reverseWords: list, error: null };
      } catch (e) {
        semanticCellData[key] = { loading: false, reverseWords: [], error: e };
      }
    };
    // 加载所有 type=25 单元格(force=true 时强制刷新,用于 semantic-link-updated 事件)
    const loadAllSemanticCells = (force = false) => {
      const tableData = table1.value.tableData || [];
      const cols = table1.value.columnsId || [];
      const types = table1.value.columnsType || [];
      for (const row of tableData) {
        cols.forEach((colId: any, idx: number) => {
          if (types[idx] === FieldEnum.语义关联) {
            loadSemanticCell(row.id, colId, force);
          }
        });
      }
    };
    // 计算单元格的合并展示词列表(正向历史 + 反向聚合,去重保留反向带 id 版本)
    const getSemanticWords = (row: any, column: string | number, columnIndex: number) => {
      const key = `${row.id}-${column}`;
      const cellData = semanticCellData[key];
      const reverseWords = cellData?.reverseWords || [];
      const reverseTexts = new Set(
        reverseWords.map((w: any) => (w.contextText || w.itemValue || '').toString().trim()).filter(Boolean)
      );
      // 正向历史值(row[column] 同步可得)
      const forwardRaw = row[column];
      let forwardTexts: string[] = [];
      if (forwardRaw && typeof forwardRaw === 'string') {
        forwardTexts = forwardRaw.split(',').map((s: string) => s.trim()).filter(Boolean);
      } else if (forwardRaw != null && forwardRaw !== '') {
        forwardTexts = [String(forwardRaw)];
      }
      // 列配置的 note_id(正向词的 noteId 兜底来源)
      const colConfig = columns.value[columnIndex];
      const colProps = safeParseJson(colConfig?.property);
      const forwardNoteId = colProps?.note_id;
      const words: any[] = [];
      // 1. 正向非碰撞词(与反向无文本冲突时展示,无 data-link-id)
      for (const text of forwardTexts) {
        if (!reverseTexts.has(text)) {
          words.push({ text, linkId: '', noteId: forwardNoteId, blockId: '' });
        }
      }
      // 2. 反向词(NoteNotelink 内部不丢词,即使 contextText 相同也各自独立展示)
      for (const w of reverseWords) {
        const text = (w.contextText || w.itemValue || '').toString().trim() || '未命名';
        words.push({ text, linkId: w.id, noteId: w.noteId, blockId: w.blockId });
      }
      return words;
    };
    // 点击词 span:提取 data-link-id → 打开 note modal(R7 case4 笔记不可访问在此拦截)
    const onSemanticWordClick = (word: any, row: any, column: string | number, columnIndex: number) => {
      const noteId = word.noteId;
      if (!noteId) {
        // R7 分支4:笔记不可访问 → 不打开笔记,提示错误
        message.error('笔记不可访问');
        return;
      }
      note.visible = true;
      note.id = noteId;
      note.tableId = datasheetID.value;
      note.recordId = row.id;
      // 词级跳转非反向创建,清空反向上下文
      note.linkColumnId = null;
      note.linkItemId = null;
      // 反向词有 data-link-id(精确定位);正向历史词为空字符串(走兜底回退)
      note.linkId = word.linkId != null ? String(word.linkId) : '';
      // 当前多维表格归属笔记 id — 用于弹框内 forward 锚点点击跳转
      note.tableNoteId = getCurrentTableNoteId();
    };
    // U4 最小状态机:loading/empty/error/data
    const getSemanticCellState = (row: any, column: string | number) => {
      const key = `${row.id}-${column}`;
      const cellData = semanticCellData[key];
      if (!cellData) return 'loading';
      if (cellData.error) return 'error';
      if (cellData.loading) return 'loading';
      const words = getSemanticWords(row, column, 0);
      if (words.length === 0) return 'empty';
      return 'data';
    };
    // U4 error 态点击重试
    const retrySemanticCell = (rowId: string | number, colId: string | number) => {
      loadSemanticCell(rowId, colId, true);
    };
    // U5/U2: 单元格反向定位（笔记→单元格）
    // 调用来源：consumeLocateQuery（URL locate 参数消费）
    // payload: { linkDwTableId, linkColumnId, linkItemId, linkRecordId }
    // 实现:vxe-table scrollToRow + scrollToColumn → getCellNode → scrollIntoView + 高亮 class
    const onSemanticLinkLocate = (payload: LocatePayload) => {
      try {
        const { linkDwTableId, linkColumnId, linkItemId, linkRecordId } = payload || {};
        if (!linkColumnId || !linkItemId) {
          message.warning('该引用缺少单元格定位信息');
          return;
        }
        // 校验当前表格是否匹配(若不匹配,提示用户切换表格页签)
        if (linkDwTableId && `${linkDwTableId}` !== `${datasheetID.value}`) {
          message.warning(`该引用指向表格 ${linkDwTableId},请切换到对应表格查看`);
          return;
        }
        const xTableRef = xTable.value;
        if (!xTableRef) {
          message.warning('表格实例未就绪,无法定位');
          return;
        }
        // 找到目标行:按 linkItemId 匹配 tableData 中每行的 itemId[colId]
        const tableData = table1.value.tableData || [];
        const targetRow = tableData.find((r: any) => {
          const itemId = r.itemId?.[linkColumnId];
          return `${itemId}` === `${linkItemId}`;
        }) || (linkRecordId ? tableData.find((r: any) => `${r.id}` === `${linkRecordId}`) : null);
        if (!targetRow) {
          message.warning('未找到目标单元格所在行');
          return;
        }
        // 找到目标列 field
        const colField = `${linkColumnId}`;
        // vxe-table scrollToRow + scrollToColumn
        const tableInstance: any = (xTableRef as any).xTable || xTableRef;
        try {
          if (typeof tableInstance.scrollToRow === 'function') {
            tableInstance.scrollToRow(targetRow);
          }
          if (typeof tableInstance.scrollToColumn === 'function') {
            tableInstance.scrollToColumn(colField);
          }
        } catch (e) {
          console.warn('vxe-table 滚动失败,降级处理:', e);
        }
        // 延迟一帧后获取 cell DOM 并 scrollIntoView + 高亮(等待 vxe-table 滚动完成)
        nextTick(() => {
          setTimeout(() => {
            try {
              const cellNode = (xTableRef as any).getCellNode?.(targetRow, colField)
                || (xTableRef as any).getCellNode?.(targetRow, { field: colField });
              if (!cellNode) {
                message.info('请手动滚动至该单元格');
                return;
              }
              cellNode.scrollIntoView({ block: 'center', behavior: 'smooth' });
              // 高亮 class(与 U4 cell.click 分支共用)
              cellNode.classList.add('semantic-located-highlight');
              setTimeout(() => {
                cellNode.classList.remove('semantic-located-highlight');
              }, 2000);
            } catch (e) {
              console.warn('获取单元格 DOM 失败:', e);
              message.info('请手动滚动至该单元格');
            }
          }, 200);
        });
      } catch (e) {
        console.error('semantic-link-locate 处理失败:', e);
        message.error('跳转失败,请稍后重试');
      }
    };
    // U2: 消费 URL locate 参数，在新标签页打开后定位目标行
    // linkDwTableId 取自 route.params.tableId（即 datasheetID），不从 query 读取
    let locateConsumed = false;
    let locateCleanupTimer: ReturnType<typeof setTimeout> | null = null;
    // 安全提取 route.query 值（Vue Router 4 中可能为 string | string[] | undefined）
    const queryToStr = (v: unknown): string =>
      Array.isArray(v) ? String(v[0] ?? '') : (v == null ? '' : String(v));
    const cleanupLocateQuery = () => {
      const { locateColumnId: _c, locateItemId: _i, locateRecordId: _r, ...rest } = route.query;
      router.replace({ query: rest }).catch(() => {});
    };
    const consumeLocateQuery = (tableData: any[] | undefined) => {
      if (locateConsumed) return;
      const { locateColumnId, locateItemId, locateRecordId } = route.query;
      const colId = queryToStr(locateColumnId);
      const itemId = queryToStr(locateItemId);
      if (!colId || !itemId) return;
      // tableData 非数组（null/undefined）说明数据未加载，跳过等后续 watch
      // 空数组也跳过：数据可能尚未返回，等后续 watch 触发；真空表为边缘情况
      if (!Array.isArray(tableData) || tableData.length === 0) return;
      if (!xTable.value) return;
      locateConsumed = true;
      const hide = message.loading({ content: '正在定位目标行…', duration: 0 });
      try {
        onSemanticLinkLocate({
          linkDwTableId: route.params.tableId,
          linkColumnId: colId,
          linkItemId: itemId,
          linkRecordId: queryToStr(locateRecordId) || undefined,
        });
      } finally {
        // 等待 onSemanticLinkLocate 内部异步滚动（nextTick + setTimeout(200)）完成后关闭 loading
        locateCleanupTimer = setTimeout(() => {
          hide();
          // 无论成功/失败，清理 locate query（保留其他 query 如 code 嵌入模式）
          cleanupLocateQuery();
        }, 500);
      }
    };
    const getTags = (val: any) => {
      if (!val) return [];
      if (Array.isArray(val)) return val; // 本身就是数组
      if (typeof val === 'string') return val.split(','); // 是逗号分割的字符串
      return [val]; // 兜底：如果是数字或其他类型，当作单一元素的数组
    };
    // 多行文本选词关联
    const textLink = reactive({
      visible: false,
      columnId: null as string | null,
      tableId: null as string | null,
      recordId: null as string | number | null,
      content: '',
      selectedText: '',
      selectedTableId: null as string | null,
      existingLinks: [] as Array<{ text: string; tableName: string; tableId: string }>,
      cancel() {
        textLink.visible = false;
        textLink.selectedText = '';
        textLink.selectedTableId = null;
      },
      handleTextSelection() {
        const selection = window.getSelection();
        const selectedText = selection?.toString()?.trim();
        if (selectedText) {
          textLink.selectedText = selectedText;
        }
      },
      async confirmLink() {
        if (!textLink.selectedText || !textLink.selectedTableId) {
          return message.warning('请先选择文本和数据表');
        }

        // 找到选中的数据表
        const relatedTable = datasheet.value.state.list?.find((item: any) => item.id === textLink.selectedTableId);
        if (!relatedTable) {
          return message.error('未找到关联的数据表');
        }

        // 获取当前记录
        const record = table1.value.list.find((item: any) => item.id === textLink.recordId);
        if (!record) {
          return message.error('未找到当前记录');
        }

        // 更新单元格内容，添加关联标记（只包含表ID）
        const linkMarker = `[${textLink.selectedText}](#table:${textLink.selectedTableId})`;
        const updatedContent = textLink.content.replace(
          textLink.selectedText,
          linkMarker
        );

        // // 调用更新 API
        await table.update({
          items: [{
            id: record.itemId[textLink.columnId!],
            name: columns.value.find((c: any) => `${c.id}` === `${textLink.columnId}`)?.name,
            value: updatedContent,
            columnId: textLink.columnId!,
            dwtId: datasheetID.value
          }],
          viewId,
          id: textLink.recordId,
        });

        // 添加到已有关联列表
        const newLink = {
          text: textLink.selectedText,
          tableName: relatedTable.name,
          tableId: textLink.selectedTableId
        };
        textLink.existingLinks.push(newLink);

        // 更新内容并清空选择
        textLink.content = updatedContent;
        textLink.selectedText = '';
        textLink.selectedTableId = null;

        message.success('关联成功');
      },
      async removeLink(index: number) {
        const link = textLink.existingLinks[index];
        if (!link) return;

        // 从文本内容中移除关联标记，将 [text](#table:tableId) 替换为 text
        const linkMarker = `[${link.text}](#table:${link.tableId})`;
        const updatedContent = textLink.content.replace(linkMarker, link.text);

        // 获取当前记录
        const record = table1.value.list.find((item: any) => item.id === textLink.recordId);
        if (!record) {
          return message.error('未找到当前记录');
        }

        // // 调用更新 API
        await table.update({
          items: [{
            id: record.itemId[textLink.columnId!],
            name: columns.value.find((c: any) => `${c.id}` === `${textLink.columnId}`)?.name,
            value: updatedContent,
            columnId: textLink.columnId!,
            dwtId: datasheetID.value
          }],
          viewId,
          id: textLink.recordId,
        });

        // 从已有关联列表中删除
        textLink.existingLinks.splice(index, 1);

        // 更新内容
        textLink.content = updatedContent;

        message.success('已删除关联');
      },
      // 解析文本中的关联链接
      parseContent(content: string) {
        if (!content) return [];

        // 匹配 [text](#table:tableId) 格式
        const regex = /\[([^\]]+)\]\(#table:([^)]+)\)/g;
        let match;
        let lastIndex = 0;
        const parts = [];

        while ((match = regex.exec(content)) !== null) {
          // 添加前面的普通文本
          if (match.index > lastIndex) {
            parts.push({
              type: 'text',
              content: content.substring(lastIndex, match.index)
            });
          }

          // 添加链接
          const tableId = match[2];
          const relatedTable = datasheet.value.state.list?.find((item: any) => item.id === tableId);
          parts.push({
            type: 'link',
            text: match[1],
            tableId: tableId,
            tableName: relatedTable?.name || '未知表格'
          });

          lastIndex = regex.lastIndex;
        }

        // 添加剩余的文本
        if (lastIndex < content.length) {
          parts.push({
            type: 'text',
            content: content.substring(lastIndex)
          });
        }

        return parts;
      },
      // 点击链接按钮
      handleLinkClick(tableId: string) {
        const relatedTable = datasheet.value.state.list?.find((item: any) => `${item.id}` === tableId);
        if (relatedTable) {
          table.state.related = {
            name: relatedTable.name,
            type: FieldEnum.链接多行文本,
            id: tableId,
            cell: {},
            record: []
          };
          table.state.visible = true;
        }
      },
      // 重建已有关联列表
      rebuildExistingLinks() {
        const parts = textLink.parseContent(textLink.content);
        const links = parts
          .filter((part: any) => part.type === 'link')
          .map((part: any) => ({
            text: part.text,
            tableName: part.tableName,
            tableId: part.tableId
          }));
        textLink.existingLinks = links;
      },
    });

    const edit = reactive({
      visible: false,
      current: {},
      click(item) {
        item = columns.value.find(column => `${column.id}` === item.field);
        if (item.type === FieldEnum.双向关联) {
          if (table1.value.tableData.some(v => v[item.id] || v[item.id] === '')) return message.warning('请先对当前绑定的数据表进行解绑');
        }
        edit.visible = true;
        edit.current = item;
        edit.formState.title = item.name;
        edit.formState.type = item.type;
        if ([FieldEnum.单选, FieldEnum.多选].includes(item.type)) {
          edit.formState.property = safeParseJson(item.property)?.select;
        }
        if ([FieldEnum.双向关联].includes(item.type)) {
          edit.formState.property = safeParseJson(item.property)?.table_id;
        }
        if ([FieldEnum.语义关联].includes(item.type)) {
          edit.formState.property = safeParseJson(item.property)?.note_id;
        }
        if ([FieldEnum.lookUp].includes(item.type)) {
          edit.formState.property = safeParseJson(item.property);
        }
      },
      cancel() {
        edit.visible = false;
        edit.current = null;
      },
      options: [],
      formState: {
        title: null,
        type: null,
        property: null,
      }
    });
    // U2: 六类基础列默认值（property JSON default 键，与后端 JSON 契约一致）
    // 复选框 default "true"/"false" 映射为存储值 '0'(勾选)/'1'(未勾选)——np-checkbox 消费 '0'/'1'
    const DEFAULTABLE_TYPES = [FieldEnum.多行文本, FieldEnum.数字, FieldEnum.单选, FieldEnum.多选, FieldEnum.日期, FieldEnum.复选框];
    const getNewRecordDefaultValue = (column: any) => {
      if (!DEFAULTABLE_TYPES.includes(column.type)) return '';
      const dv = safeParseJson(column.property)?.default;
      if (dv == null || `${dv}`.trim() === '') return '';
      if (column.type === FieldEnum.复选框) return dv === 'true' ? '0' : '1';
      return `${dv}`;
    };
    const toolbarAction = {
      "添加记录": async key => {
        if (columns.value.length === 0) {
          return message.warning('请先配置字段在进行添加');
        }
        const { data } = await useFetch('/system/record/add').post({
          items: columns.value.map(item => {
            return {
              name: item.name,
              columnId: item.id,
              value: getNewRecordDefaultValue(item),
              dwtId: datasheetID.value
            }
          }),
          viewId: viewId,
        }).json();
        if (data?.value) {
          table.get.list();
        }
      },
      '行高': key => {
        rowConfig.height = key;
      },
      '分组': key => {
        store.grouping(key);
      },
    }

    // watch(() => datasheetID.value, () => {
    //   fetch();
    // });


    const input = ref<HTMLElement | null>();
    const setRef = (el: HTMLElement) => {
      input.value = el;
      if (el) {
        el.focus?.()
      }
    }
    const onToolbarClick = async (item: any, key: any) => {
      toolbarAction[item.title]?.(key);
    }

    const fetch = () => {
      for (const key in table.get) {
        const fn = table.get[key];
        fn();
      }
    }
    let sortable = null;
    let sortable1 = null;

    const updateSort = async (type = 'row', list = {}) => {
      const api = type === 'row' ? '/system/record/updateSort' : '/system/column/updateSort';
      await useFetch(api).post(list).json();
      table.get.columns();
      table.get.list();
    }

    onMounted(() => {

      const data = datasheet.value.state.all.map(item => {
        return {
          ...item,
          label: item.name,
          value: item.id
        }
      });
      edit.options = data;

      // 使用递归 nextTick 替代 setTimeout，确保 DOM 已渲染
      const initSortable = (retryCount = 0) => {
        if (!xTable.value?.$el) {
          if (retryCount < 10) {
            nextTick(() => initSortable(retryCount + 1));
          }
          return;
        }

        const tbody = xTable.value.$el.querySelector('.vxe-table--body tbody');
        const headerRow = xTable.value.$el.querySelector('.vxe-header--row');

        if (tbody) {
          sortable = useSortable({
            el: tbody,
            options: {
              onEnd: (sortableEvent: any) => {
                const index = sortableEvent.newIndex as number;
                const oldIndex = sortableEvent.oldIndex as number;
                const { tableData } = xTable.value.getTableData();

                updateSort('row', {
                  sorts: [
                    { sort: oldIndex, id: tableData[index].id },
                    { sort: index, id: tableData[oldIndex].id }
                  ]
                });
              }
            }
          });
        }

        if (headerRow) {
          sortable1 = useSortable({
            el: headerRow,
            options: {
              filter: '.col--checkbox',
              onEnd: (sortableEvent: any) => {
                const targetThElem = sortableEvent.item;
                const newIndex = sortableEvent.newIndex as number;
                const oldIndex = sortableEvent.oldIndex as number;
                const { fullColumn } = xTable.value.getTableColumn();
                const wrapperElem = targetThElem.parentNode as HTMLElement;
                const newColumn = fullColumn[newIndex];
                const oldColumn = fullColumn[oldIndex];

                if (newColumn?.fixed || newColumn?.type === 'checkbox') {
                  const oldThElem = wrapperElem.children[oldIndex] as HTMLElement;
                  if (newIndex > oldIndex) {
                    wrapperElem.insertBefore(targetThElem, oldThElem);
                  } else {
                    wrapperElem.insertBefore(targetThElem, oldThElem ? oldThElem.nextElementSibling : oldThElem);
                  }
                  return;
                }
                updateSort('col', {
                  sorts: [
                    { sort: oldIndex, id: newColumn['field'] },
                    { sort: newIndex, id: oldColumn['field'] }
                  ]
                });
              }
            }
          });
        }
      };

      nextTick(() => initSortable());

      // U2: 组件挂载后 xTable ref 已就绪，补一次定位消费
      // 覆盖 immediate watch 时 xTable 未绑定导致跳过的场景（如 store 缓存命中 tableData 已有数据）
      consumeLocateQuery(table1.value.tableData);

      // 监听语义关联更新事件
      Bus.on((event: string) => {
        try {
          const data = JSON.parse(event);
          if (data.type === 'semantic-link-updated') {
            // U4: 创建/删除后强制重新查询 NoteNotelink 聚合并刷新单元格渲染
            loadAllSemanticCells(true);
          }
        } catch (e) {
          console.warn('Invalid event data:', event);
        }
      });
    });
    // U4: 表格数据加载后,异步加载所有 type=25 单元格的 NoteNotelink 聚合数据
    // immediate: 父组件 views/base/index.vue 异步触发 store.getTableList,baseTable 组件挂载可能晚于
    // tableData 首次赋值;若不立即执行,后续无引用变化时 watch 永不触发,单元格卡在"加载中…"
    watch(() => table1.value.tableData, (tableData) => {
      loadAllSemanticCells();
      // U2: 消费 URL locate 参数，定位目标行后清理参数
      consumeLocateQuery(tableData);
    }, { immediate: true });
    onUnmounted(() => {
      sortable?.instance.destroy?.();
      sortable1?.instance.destroy?.();
      // U2: 清理定位定时器，防止组件卸载后 router.replace 干扰新导航
      if (locateCleanupTimer) clearTimeout(locateCleanupTimer);
    })


    provide('table', xTable);
    provide('tableState', table);
    provide('store', {
      id,
      store,
      columns
    });
    const toNote = (rowId, colId, table1) => {
      const row = table1.list.find(item => item.id === rowId);
      const blockId = row.linkBlockId[colId];
      const noteId = row.linkNoteId[colId];
      if (noteId && blockId) {
        window.open(`/docx/${noteId}/${blockId}`);
      }

    }

    return {
      edit,
      note,
      notePicker,
      exportModal,
      importState,
      excelImport,
      importFileInput,
      exportDisabled,
      openNotePicker,
      textLink,
      datasheet,
      FieldEnum,
      toNote,
      table,
      table1,
      input,
      setRef,
      viewId,
      getTags,
      datasheetID,
      relatedRef,
      columns,
      fetch,
      fields,
      items,
      xTable,
      rowConfig,
      treeConfig,
      onToolbarClick,
      getSemanticWords,
      onSemanticWordClick,
      getSemanticCellState,
      retrySemanticCell,
    }
  }
})
</script>

<style scoped lang="scss">
:deep(.vxe-table--render-default .vxe-body--column.col--ellipsis > .vxe-cell .vxe-tree-cell) {
  overflow: unset;
}

:deep(.ant-select-selector) {
  z-index: 1;
}

.semantic-word-list {
  display: inline;

  .semantic-word {
    /* U4 a11y:词级 button 元素原生键盘可达,重置外观保留链接视觉 */
    appearance: none;
    background: transparent;
    border: none;
    padding: 0;
    margin: 0;
    color: #1890ff;
    cursor: pointer;
    font: inherit;
    display: inline;

    &:hover {
      text-decoration: underline;
    }

    &:focus-visible {
      outline: 2px solid #1890ff;
      outline-offset: 2px;
      border-radius: 2px;
    }
  }

  .semantic-word-sep {
    color: inherit;
  }

  .semantic-word-loading,
  .semantic-word-empty,
  .semantic-word-error {
    color: #999;
    cursor: default;
  }

  .semantic-word-empty {
    color: #d9d9d9;
  }

  .semantic-word-error {
    color: #ff4d4f;
    cursor: pointer;
    font-weight: bold;
  }
}

/* U5: 被反向定位高亮的单元格 */
:deep(.semantic-located-highlight) {
  outline: 2px solid #faad14 !important;
  outline-offset: -2px;
  transition: outline 0.3s;
  background-color: #fffbe6 !important;
}

:deep(.vxe-table--render-default .vxe-tree-cell) {
  padding-left: 0;
}

.text-link-content-wrapper {
  .text-content-area {
    min-height: 150px;
    max-height: 300px;
    overflow-y: auto;
    padding: 16px;
    background-color: #f5f5f5;
    border-radius: 4px;
    margin-bottom: 16px;
    cursor: text;
    user-select: text;

    .content-text {
      white-space: pre-wrap;
      word-wrap: break-word;
      line-height: 1.6;
      color: #333;
    }

    &::selection {
      background-color: #1890ff;
      color: #fff;
    }
  }

  .selection-actions {
    padding: 12px;
    background-color: #e6f7ff;
    border: 1px solid #91d5ff;
    border-radius: 4px;
    margin-bottom: 16px;

    .action-buttons {
      display: flex;
      align-items: center;
      margin-top: 8px;

      .action-label {
        margin-right: 12px;
        font-weight: 500;
        color: #333;
      }
    }
  }

  .existing-links {
    padding: 12px;
    background-color: #f9f9f9;
    border-radius: 4px;
  }


}
</style>