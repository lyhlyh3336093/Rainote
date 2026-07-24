<template>
  <vxe-table ref="xTable" :column-config="{ resizable: true }" :data="table.state.tableData" :tree-config="treeConfig"
    height="500px" keep-source show-overflow :loading="table.state.loading" :row-config="{ keyField: 'id', }"
    :checkbox-config="{ checkRowKeys: data.record, }">
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
      table.state.loading = false;
    })
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
    }
  }
})
</script>

<style scoped lang="scss">
:deep(.vxe-table--render-default .vxe-body--column.col--ellipsis > .vxe-cell .vxe-tree-cell) {
  overflow: unset;
}
</style>
