<template>
  <vxe-table ref="xTable" :column-config="{ resizable: true }" :data="table.state.tableData" :tree-config="treeConfig"
    height="500px" keep-source show-overflow :loading="table.state.loading" :row-config="{ keyField: 'id', }"
    :checkbox-config="{ checkRowKeys: data.record, }" :row-class-name="rowClassName">
    <!-- 双向链接列：checkbox 首列（沿用现有表示，R5） -->
    <vxe-column v-if="data.type!==FieldEnum?.lookUp" type="checkbox" width="50">
      <template #header="{ checked, indeterminate }">
        <a-checkbox :disabled="data.type === 24" :checked="checked" :indeterminate="indeterminate"
          @change="xTable.toggleAllCheckboxRow()" />
      </template>
      <template #checkbox="{ row, checked, indeterminate }">
        <a-checkbox :disabled="data.type === 24" :checked="checked" :indeterminate="indeterminate"
          @change.stop="xTable.toggleCheckboxRow(row)" />
      </template>
    </vxe-column>
    <!-- lookup 列：checkbox 已隐藏，渲染替代首列给已选（置顶）行加"已选"标记（R6），复用 U2 selectedSet -->
    <vxe-column v-else width="60" title="已选">
      <template #default="{ row }">
        <span v-if="selectedSet.has(String(row.id))" class="pin-selected-tag">已选</span>
      </template>
    </vxe-column>

    <template v-for="(column, index) in table.state.columnsId" :key="column">
      <vxe-column :edit-render="{}" :field="`${column}`" show-header-overflow show-overflow="title"
        :title="table.state.columnsName[index]">
        <template #edit="{ row }">
          <component :is="fields[table.state.columnsType[index]].value" :value="row[column]"
            @change="(e) => (row[column] = e)" :props="fields[table.state.columnsType[index]].props"
            :type="table.state.columnsType[index]"></component>
        </template>
      </vxe-column>
    </template>
  </vxe-table>
</template>

<script lang="tsx">
import { defineComponent, reactive, ref, onMounted, } from 'vue';
import type { VxeTableInstance } from 'vxe-table'
import { storeToRefs } from 'pinia';
import { useStore } from '../../../stores/table';
import { useFetch } from '../../../hooks';
import { NpInput, NpPicker, NpCheckbox, NpSelect } from '../../index.js'
import { FieldEnum } from "../../../enum/index.ts";

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
  props: ['data'],
  components: { NpCheckbox, NpInput, NpPicker, NpSelect },
  setup(props, ctx) {
    const store: any = useStore();
    const { columns, fields, columnsId, columnsName, columnsType } = storeToRefs(store);
    const xTable = ref<VxeTableInstance>();
    const treeConfig = ref({
      transform: false,
      rowField: 'id',
      parentField: 'parentId'
    });
    // 已选集合快照（U2/U3 复用）。打开面板时按 data.record 一次性计算，不随勾选变化重算（R1/AE3）。
    // P1 类型不匹配修正：data.record 来自 .split(',') 为 string[]，row.id 为后端 Long(number)，
    // 直接 Array.includes 会因 === 严格相等失败导致置顶静默失效——统一 String 化并用 Set.has。
    const selectedSet = ref<Set<string>>(new Set());
    const selectedCount = ref(0);
    // 表格视图相关
    const table = {
      state: reactive({
        list: [],
        loading: false,
        columnsId: [],
        columnsName: [],
        tableData: [],
        columns: [],
      }) as any,
      get: {
        async columns() {
          const { data } = await useFetch(`/system/column/columnList?dwtableId=${props.data.id}`,).get().json();
          if (data?.value) {
            const res = data.value.data.filter(item => !item.isShow);
            table.state.columnsId = res.map(item => `${item.id}`);
            table.state.columnsName = res.map(item => item.name);
            table.state.columnsType = res.map(item => item.type);
            table.state.columns = res;
          }
        },
        async list() {
          const { data } = await useFetch(`/system/record/dataList?dwtableId=${props.data.id}`,).get().json();
          if (data?.value?.data?.length > 0) {
            const list = data.value.data.filter(item => item?.tableData);
            table.state.list = list;
            table.state.tableData = list.map(item => {
              item.tableData['id'] = item.id;
              for (const key in item.tableData) {
                const column = table.state.columns.find(v => `${v.id}` === key);
                if (column) {
                  item.tableData['type'] = column.type;
                  if ([FieldEnum.多选, FieldEnum.双向关联, FieldEnum.集合运算].includes(column.type)) {
                    item['tableData'][column.id] = item['tableData'][column.id].split(',').filter(item => item)
                  }
                  if (item['tableData'][column.id]?.length === 0) {
                    item['tableData'][column.id] = null;
                  }
                }
              }
              return item.tableData;
            });
          }
        },
      }
    }
    onMounted(async () => {
      table.state.loading = true;
      await Promise.allSettled([table.get.list(), table.get.columns()]);
      // 稳定分区：已选记录置顶，组内保留后端原顺序（R4）。快照语义——不设 watcher 重算（R1/AE3）。
      // data.record 为空或 undefined（如列配置预览路径）时跳过分区，列表按原顺序展示（R3）。
      const records = props.data.record as any[];
      if (records && records.length > 0) {
        selectedSet.value = new Set(records.map(String));
        const tableData = table.state.tableData as any[];
        const selectedRows = tableData.filter(row => selectedSet.value.has(String(row.id)));
        const unselectedRows = tableData.filter(row => !selectedSet.value.has(String(row.id)));
        table.state.tableData = [...selectedRows, ...unselectedRows];
        selectedCount.value = selectedRows.length;
      } else {
        selectedSet.value = new Set();
        selectedCount.value = 0;
      }
      table.state.loading = false;
    })
    // 行类名：两组都非空时给已选组末行加边界类，scoped CSS 渲染下边框作分组分隔（R7）。
    // 不注入合成数据行，避免干扰 checkbox/keyField 逻辑。
    const rowClassName = ({ row, rowIndex }: { row: any; rowIndex: number }) => {
      const total = (table.state.tableData as any[]).length;
      // 已选组末行（仅当已选组与未选组都非空）
      if (selectedCount.value > 0 && selectedCount.value < total && rowIndex === selectedCount.value - 1) {
        return 'pin-group-boundary';
      }
      return '';
    };
    return {
      table,
      FieldEnum,
      columns,
      columnsId,
      columnsName,
      columnsType,
      fields,
      xTable,
      treeConfig,
      selectedSet,
      selectedCount,
      rowClassName,
    }
  }
})
</script>

<style scoped lang="scss">
:deep(.vxe-table--render-default .vxe-body--column.col--ellipsis > .vxe-cell .vxe-tree-cell) {
  overflow: unset;
}

/* 分组分隔：已选组末行下边框（R7）。两组都非空时由 rowClassName 给末行加类。 */
:deep(.vxe-table--render-default .vxe-body--row.pin-group-boundary) .vxe-body--column {
  border-bottom: 2px solid #d9d9d9;
}

/* lookup 列"已选"标记（R6）：小型内联标签。 */
.pin-selected-tag {
  display: inline-block;
  padding: 0 6px;
  font-size: 12px;
  line-height: 18px;
  color: #1677ff;
  background: #e6f4ff;
  border: 1px solid #91caff;
  border-radius: 4px;
}
</style>
