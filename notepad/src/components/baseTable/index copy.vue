<template>
  <div>
    <Toolbar @click="(item, key) => onToolbarClick(item, key)">
      <a-button @click="table.delete" type="primary" danger class="m-2.5">
        <delete-outlined />
        删除记录
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
      <vxe-column type="checkbox" width="50">
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
            <template v-if="[FieldEnum.多选, FieldEnum.双向关联, FieldEnum.集合运算].includes(table1.columnsType[index])">
              <template v-if="row[column]">
                <template v-if="Array.isArray(row[column])">
                  <template v-for="(item, z) in row[column]" :key="item">
                    <a-tag v-if="z < 3">{{ item?.label || item }}</a-tag>
                  </template>
                </template>
                <template v-else>{{ row[column] }}</template>
                <a-tag v-if="row[column].length > 3">
                  +{{ row[column].length - 3 }}
                </a-tag>
              </template>
            </template>
            <template v-else-if="table1.columnsType[index] === FieldEnum.语义关联">
              <a-button @click="toNote(row.id, column, table1)" v-if="row[column]?.length > 0">{{ row[column]
              }}</a-button>
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
      v-model:open="table.state.visible" destroyOnClose @ok="table.cell.related"
      :ok-button-props="{ disabled: table.state.related.type === FieldEnum.集合运算 }"
      :cancel-button-props="{ disabled: table.state.related.type === FieldEnum.集合运算 }">
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
        <Editor @save="note.save" :id="note.id" :tableId="note.tableId" :recordId="note.recordId" />
      </div>
    </a-modal>

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
import { defineComponent, reactive, ref, provide, onMounted, inject, Ref, onUnmounted, nextTick } from 'vue';
import type { VxeTableInstance } from 'vxe-table'
import Toolbar from './toolbar/index.vue';
import { message } from 'ant-design-vue';
import { storeToRefs } from 'pinia';
import { useStore } from '../../stores/table';
import { useFetch, useSortable } from '../../hooks';
import Related from './related/index.vue';
import Form from './form/index.vue';
import { clone } from 'xe-utils';
import Edit from './field/edit.vue';
import { FieldEnum } from "@/enum";
import { safeParseJson, Bus } from "@/utils";
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
  components: { Toolbar, Related, Form, Edit, Editor },
  setup(props, ctx) {
    const store: any = useStore();
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
        if (cellValue !== column.model.value) {
          const _type = table1.value.columnsType[$columnIndex - 1];
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

          // 查找关联的表格
          const related = datasheet.value.state.all.find((item: any) => `${item.id}` === `${targetId}`);
          if (!related) return;

          // 查找当前记录
          const record = table1.value.list.find((item: any) => `${item.id}` === `${cell.id}`);
          if (!record) return;

          // 设置关联状态
          table.state.related = {
            type,
            ...related,
            id: targetId,
            cell,
            record: record['linkRecordId']?.[cell.column]?.split(',') || []
          };
          table.state.visible = true;
        },
        related() {
          const rows = relatedRef.value.xTable.getCheckboxRecords();
          if (rows.length === 0) return message.warning('请选择需要关联的数据');
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
          if (!row['itemId'][id]) return;
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
          table.get.list();
          table.state.visible = false;
        }
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
      save() { },
      cancel() {
        note.visible = false;
      },
    })

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
              value: '',
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

      // 监听语义关联更新事件
      Bus.on((event: string) => {
        try {
          const data = JSON.parse(event);
          if (data.type === 'semantic-link-updated') {
            console.log(data)

          }
        } catch (e) {
          console.warn('Invalid event data:', event);
        }
      });
    });
    onUnmounted(() => {
      sortable?.instance.destroy?.();
      sortable1?.instance.destroy?.()
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
      textLink,
      datasheet,
      FieldEnum,
      toNote,
      table,
      table1,
      input,
      setRef,
      viewId,
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
