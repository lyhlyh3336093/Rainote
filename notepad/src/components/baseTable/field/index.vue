<template>
  <div v-bind="$attrs" class="field-list overflow-hidden">
    <a-menu id="menu" ref="dragRef">
      <!-- <div class="max-h-80 overflow-auto"> -->
      <a-menu-item :disabled="item.isShow === 1" v-for="item in columns" :key="item.id">
        <div class="field-item">
          <div class="field-item-title ellipsis">
            <span>{{ item.name }}</span>
          </div>
          <div class="field-item-action">
            <template v-if="item.type===FieldEnum.lookUp">
              <clear-outlined @click.stop="isDeduplicate(item)" />
            </template>
            <template v-if="item.isShow === 0">
              <eye-outlined @click.stop="visibleChange(item)" />
            </template>
            <template v-else>
              <eye-invisible-outlined @click.stop="visibleChange(item)" />
            </template>
            <edit-outlined :class="{
              disabled: item.isEdit,
            }" @click="editField(item)" />
            <delete-outlined :class="{ disabled: deleting }" @click="deleteField(item)" />
          </div>
        </div>
      </a-menu-item>
      <!-- </div> -->
      <div>
        <a-menu-divider />
        <a-menu-item @click="visible = true"> 添加字段 </a-menu-item>
      </div>
    </a-menu>
    <a-modal @cancel="cancel" v-model:open="visible" :footer="null" centered :title="edit ? '编辑字段' : '新增字段'"
      destroyOnClose>
      <Edit @visible="cancel" :formState="formState" :edit="edit" />
    </a-modal>
  </div>
</template>

<script lang="tsx">
import {
  defineComponent,
  ref,
  watch,
  inject,
  reactive,
  Ref,
  onMounted,
  computed,
} from "vue";
import { useFetch, useSortable } from "../../../hooks";
import { Modal } from "ant-design-vue";
import { storeToRefs } from "pinia";
import Edit from "./edit.vue";
import { FieldEnum } from "@/enum";
import { useStore } from '../../../stores/menu';
import { safeParseJson } from "@/utils";
export default defineComponent({
  name: "Field",
  components: { Edit },
  setup(props, ctx) {
    const menuStore = useStore();
    const { noteList } = storeToRefs(menuStore)
    const form = ref(null);
    const visible = ref(false);
    const edit = ref(null);
    // U4: 删除进行中标记，防止重复点击
    const deleting = ref(false);
    const { store }: any = inject("store");
    const datasheet: Ref<any> = ref(inject("datasheet"));
    const { columns, datasheetID, fields } = storeToRefs(store);
    const dragRef = ref();
    if (noteList.value.length === 0) {
      menuStore.getMenu();
    }
    const options = computed(() => {
      return datasheet.value.state.all.map((item) => {
        return {
          ...item,
          label: item.name,
          value: item.id,
        };
      });
    });


    const updateSort = async (type = "row", list = {}) => {
      const api =
        type === "row"
          ? "/system/record/updateSort"
          : "/system/column/updateSort";
      await useFetch(api).post(list).json();
      store.getColumns();
      store.getTableList();
    };
    // watch([dragRef], ([dragRef]) => {
    //   useSortable({
    //     el: dragRef.$el,
    //     options: {
    //       animation: 200,
    //       handle: ".ant-menu-item",
    //       onEnd(sortableEvent) {
    //         const newIndex = sortableEvent.newIndex as number;
    //           const oldIndex = sortableEvent.oldIndex as number;
    //           const newColumn=columns.value[newIndex];
    //           const oldColumn = columns.value[oldIndex]
    //           updateSort('col', {
    //             sorts: [
    //               {
    //                 sort: newIndex,
    //                 id: newColumn['id'],
    //               },
    //               {
    //                 sort:oldIndex ,
    //                 id: oldColumn['id'],
    //               }
    //             ]
    //           })
    //       },
    //     },
    //   });
    // });
    const visibleChange = (item) => {
      const { id, isShow, dwtableId, name, type } = item;
      const col = {
        id,
        name,
        type,
        dwtableId,
        isShow: isShow ? 0 : 1,
      };
      store?.updateColumn(col);
    };
    const deleteField = async ({ name, id, type }) => {
      if (deleting.value) return; // 防止重复点击
      // U4 R1-R3: type=25 语义关联列删除前双数据源校验与确认流程
      if (type === FieldEnum.语义关联) {
        deleting.value = true;
        try {
          // R1a: 检查 NoteDwtableItem 非空 cell（前端内存数据）
          const hasCellData = store.table.tableData.some((row) => {
            const val = row[id];
            return val != null && val !== '' && !(Array.isArray(val) && val.length === 0);
          });
          // R1b: 检查 NoteNotelink（调 API，pageSize=1 仅判断是否存在）
          let hasNotelink = false;
          try {
            const { data } = await useFetch(
              `/system/notelink/list?linkColumnId=${id}&pageSize=1&pageNum=1`
            ).get().json();
            hasNotelink = (data?.value?.total ?? 0) > 0;
          } catch (e) {
            // 权限不足或网络失败：降级为只检查 cell value
            console.warn('[deleteField] notelink 查询失败，降级为只检查 cell value', e);
          }
          const hasData = hasCellData || hasNotelink;
          if (!hasData) {
            // R2: 无数据直接删除，无对话框
            await store.deleteColumn(id);
            return;
          }
          // R3: 有数据显示确认对话框（级联清理警告）
          Modal.confirm({
            zIndex: 1070,
            title: "删除字段",
            content: `字段“${name}”包含关联数据，删除将级联清理关联笔记链接并恢复笔记文本。确认删除吗？`,
            okText: "确认删除",
            cancelText: "取消",
            okButtonProps: {
              type: "primary",
              danger: true,
            },
            centered: true,
            onOk() {
              // 返回 Promise，antd 自动禁用按钮 + loading（防重复点击）
              return store.deleteColumn(id);
            },
          });
        } finally {
          deleting.value = false;
        }
        return;
      }
      // 非 type=25 列：保持现有确认对话框逻辑
      Modal.confirm({
        zIndex: 1070,
        title: "删除字段",
        content: `确认删除字段“${name}”吗？`,
        okText: "确认",
        cancelText: "取消",
        okButtonProps: {
          type: "primary",
          danger: true,
        },
        centered: true,
        onOk() {
          return store.deleteColumn(id);
        },
      });
    };
    const filterOption = (input: string, option: any) => {
      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0;
    };
    const formState = reactive({
      title: null,
      type: null,
      property: null,
    });

    const onFinish = (values: any) => {
      const { title, type, property } = values;
      let _property: any = {
        select: `${property}`.replace(/，/gi, ","),
      };

      if (type === FieldEnum.双向关联) {
        _property = {
          table_id: property,
          table_name: options.value.find((item) => item.value === property)
            .name,
        };
      }
      if (type === FieldEnum.语义关联) {
        _property = {
          note_id: property,
          note_name: options.value.find((item) => item.value === property)
            .title,
        };
      }
      if (![FieldEnum.单选, FieldEnum.多选, FieldEnum.单向关联, FieldEnum.双向关联, FieldEnum.语义关联].includes(type)) {
        _property = {};
      }
      if (type === FieldEnum.lookUp) {
        const target = options.value.find((item) => item.value === property);
        _property = {
          "source_column_id": property,
          "source_column_name": title,
          "double_link_column_id": target.label,
          "double_link_column_name": target.value

        };
      }
      let col: any = {
        id: null,
        type: type,
        dwtableId: datasheetID.value,
        name: title,
        property: _property,
      };
      if (edit.value) {
        col.id = edit.value.id;
        store?.updateColumn(col);
        edit.value = null;
      } else {
        delete col.id;
        store?.addColumn(col);
      }
      cancel();
    };
    const cancel = () => {
      visible.value = false;
      edit.value = null;
      form?.value?.resetFields?.();
      for (const key in formState) {
        formState[key] = null;
      }
    };

    const editField = (item) => {
      if (item.isEdit) return;
      visible.value = true;
      edit.value = item;
      formState.title = item.name;
      formState.type = item.type;
      if ([FieldEnum.单选, FieldEnum.多选].includes(item.type)) {
        formState.property = safeParseJson(item.property)?.select;
      }
      if ([FieldEnum.双向关联].includes(item.type)) {
        formState.property = safeParseJson(item.property)?.table_id;
      }
      if ([FieldEnum.语义关联].includes(item.type)) {
        formState.property = safeParseJson(item.property)?.note_id;
      }
      if ([FieldEnum.数学公式, FieldEnum.集合运算, FieldEnum.lookUp].includes(item.type)) {
        formState.property = safeParseJson(item.property);
      }
    };
    const finishFailed = (e, a, b, c) => {
      console.log(e, a, b, c);
    };
    const isDeduplicate = async (item) => {
      await useFetch(`${window.config.deduplicatePath}?columnId=${item.id}`).get().json()
      store.getTableList();
    }

    return {
      finishFailed,
      filterOption,
      FieldEnum,
      isDeduplicate,
      form,
      fields,
      columns,
      datasheet,
      datasheetID,
      cancel,
      edit,
      editField,
      formState,
      onFinish,
      visible,
      dragRef,
      deleteField,
      deleting,
      visibleChange,
      options,
    };
  },
});
</script>

<style lang="scss" scoped>
.ant-dropdown-menu {
  :deep(.ant-dropdown-menu-item, .ant-dropdown-menu-submenu-title) {
    cursor: default;
  }
}

:deep(.ant-menu:not(.ant-menu-horizontal) .ant-menu-item-selected,
  .ant-menu-item:active,
  .ant-menu-submenu-title:active) {
  background-color: transparent;
  color: inherit;
}

:deep(.ant-menu-item:hover) {
  background: #f5f5f5;
}

:deep(.ant-menu-light .ant-menu-item:hover,
  .ant-menu-light .ant-menu-item-active,
  .ant-menu-light .ant-menu:not(.ant-menu-inline) .ant-menu-submenu-open,
  .ant-menu-light .ant-menu-submenu-active,
  .ant-menu-light .ant-menu-submenu-title:hover) {
  color: inherit;
}

//
//:deep(.ant-menu-item:focus-visible, .ant-menu-submenu-title:focus-visible) {
//  box-shadow: none;
//}
:deep(.ant-menu-item-disabled, .ant-menu-submenu-disabled) {
  cursor: default;
}

.field-item {
  display: flex;

  .field-item-title {
    max-width: 100px;
  }

  >div:last-child {
    margin-left: auto;

    .anticon {
      cursor: pointer;
      margin: 0 5px;

      &.disabled {
        color: rgba(0, 0, 0, 0.25);
        border-color: #d9d9d9;
        box-shadow: none;
        cursor: not-allowed;
      }
    }
  }
}
</style>