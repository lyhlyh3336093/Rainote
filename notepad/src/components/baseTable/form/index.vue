<template>
  <a-form layout="vertical" :model="state.form">
    <template
        v-for="(label, id, index) in state.table[state.index]?.columns"
        :key="id"
    >
      <a-form-item :label="label">
        <component
            disabled
            :is="fields[state.columnsType[index]].value"
            :forwardRef="setRef"
            v-model:value="state.table[state.index]['tableData'][id]"
            :props="fields[state.columnsType[index]].props"
            :type="state.columnsType[index]"
        ></component>
      </a-form-item>
    </template>
  </a-form>
</template>
<script lang="tsx">
import {defineComponent, onMounted, reactive} from "vue";
import {useFetch} from "../../../hooks";
import {FieldEnum} from "@/enum";

export default defineComponent({
  props: ["data"],
  setup(props) {
    const {data} = props;
    const {table_id, value, fields} = data;
    const state = reactive({
      form: {},
      columns: [],
      table: [],
      index: 0,
      columnsType: [],
    });
    const getColumns = async () => {
      const {data: columns} = await useFetch(
          `/system/column/columnList?dwtableId=${table_id}`
      )
          .get()
          .json();
      if (columns.value?.code === 200) {
        state.columns = columns.value.data.map((item) => {
          if (item.property) {
            item.property = eval(
                "(" + JSON.parse(JSON.stringify(item.property)) + ")"
            );
            if ([FieldEnum.单选, FieldEnum.多选].includes(item.type)) {
              item.property.select = `${item.property?.select}`
                  .split(",")
                  .map((item) => ({value: item, label: item}));
            }
          }
          return item;
        });
        state.columnsType = state.columns.map(
            (item: { type: any }) => item.type
        );
      }
    };
    const getTableList = async () => {
      const {data} = await useFetch(
          `/system/record/dataList?dwtableId=${table_id}`
      )
          .get()
          .json();
      if (data.value?.code === 200) {
        const list = data.value.data;
        const rows = list.filter((item) => value.includes(`${item.id}`));
        state.table = rows.map((item) => {
          item.tableData["id"] = item.id;
          for (const key in item.columns) {
            const column = state.columns.find(
                (v: { id: any }) => `${v.id}` === key
            );
            if (column) {
              item.tableData["type"] = column.type;
              if ([FieldEnum.多选, FieldEnum.双向关联, FieldEnum.集合运算].includes(column.type)) {
                item["tableData"][column.id] = item["tableData"]?.[column.id]
                    ?.split(",")
                    ?.filter((item: any) => item);
              }
              if (item["tableData"][column.id]?.length === 0) {
                item["tableData"][column.id] = null;
              }
              if (!item["tableData"][column.id]) {
                if ([FieldEnum.单选, FieldEnum.多选, FieldEnum.双向关联, FieldEnum.集合运算].includes(column.type)) {
                  item["tableData"][column.id] = [];
                } else {
                  item["tableData"][column.id] = '';
                }
              }
            }
          }

          // for (const key in item.tableData) {
          //   const column = state.columns.find(
          //     (v: { id: any }) => `${v.id}` === key
          //   );
          //   if (column) {
          //     item.tableData["type"] = column.type;
          //     if ([4, 21].includes(column.type)) {
          //       item["tableData"][column.id] = item["tableData"][column.id]
          //         .split(",")
          //         .filter((item: any) => item);
          //     }
          //     if (item["tableData"][column.id]?.length === 0) {
          //       item["tableData"][column.id] = null;
          //     }
          //   }
          // }
          return item;
        });
      }
    };

    onMounted(() => {
      Promise.all([getColumns(), getTableList()]);
    });
    const setRef = () => {
    };
    const onChange = (value) => {
      console.log(value);
    };
    return {
      onChange,
      setRef,
      state,
      fields,
    };
  },
});
</script>

<style lang="scss" scoped>
.ant-form {
  min-height: 500px;
  max-height: 600px;
  overflow: auto;
}
</style>