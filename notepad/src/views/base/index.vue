<template>
  <div class="np-base-table">
    <a-skeleton :loading="!state.isFinished" active>
      <Header :data="state.data" v-if="!$route.query?.code"/>
      <a-layout :class="{ code: $route.query?.code }">
        <a-layout-sider width="250px" v-if="!$route.query?.code">
          <div class="datasheet-menu">
            <div class="datasheet-list" ref="datasheetRef">
              <a-directory-tree @select="datasheet.select($event)" :height="800"
                                :fieldNames="datasheet.state.fieldNames" class="tree" :showIcon="false" blockNode
                                v-model:selectedKeys="datasheet.state.selectedKeys" :tree-data="datasheet.state.list">
                <template #title="{ dataRef }">
                  <OnClickOutside @trigger="datasheet.edit.close(dataRef)" v-if="dataRef.edit">
                    <a-input v-focus @input="datasheet.edit.change(dataRef, $event)" :value="dataRef.name"
                             class="h-10"/>
                  </OnClickOutside>
                  <div v-else class="datasheet-item flex flex-row items-center p-1 h-10">
                    <div class="flex flex-row items-center">
                      <table-outlined/>
                      <div class="w-28 ml-2 overflow-hidden text-ellipsis break-all whitespace-nowrap">
                        {{ dataRef.name }}
                      </div>
                    </div>
                    <div class="datasheet-item-action ml-auto">
                      <form-outlined class="m-2 item-action" @click.stop="onEdit(dataRef, 'rename')"/>
                      <delete-outlined class="item-action" @click.stop="datasheet.edit.delete(dataRef)"/>
                    </div>
                  </div>
                </template>
              </a-directory-tree>
            </div>
            <div class="datasheet-action">
              <a-button @click="datasheet.add">新建数据表</a-button>
            </div>
          </div>
        </a-layout-sider>
        <a-layout-content>
          <div class="layout-main">
            <a-tabs ref="tabsRef" v-model:activeKey="view.state.id" destroyInactiveTabPane type="editable-card"
                    @change="view.tabsChange($event)" @edit="onEdit">
              <template #addIcon>
                <a-dropdown :trigger="['click']">
                  <a-button type="link" @click.prevent.stop>
                    <plus-outlined/>
                  </a-button>
                  <template #overlay>
                    <Menu :list="state.menu.list" @click="view.add($event)"/>
                  </template>
                </a-dropdown>
              </template>
              <a-tab-pane v-for="(pane, index) in view.state.list" :key="pane.id"
                          :closable="index === 0 ? false : true">
                <!-- <template v-if="pane.edit">
                  <OnClickOutside @trigger="view.close(pane)">
                    <a-input v-model:value="pane.name" @change.prevent.stop />
                  </OnClickOutside>
                </template> -->
                <template #tab>
                  {{ pane.name }}
                  <a-dropdown :trigger="['click']">
                    <more-outlined class="m-1"/>
                    <template #overlay>
                      <a-menu @click="view.select($event, pane)">
                        <a-menu-item key="0"> 重命名</a-menu-item>
                        <!-- <a-menu-item key="1">
                          保护视图
                        </a-menu-item> -->
                      </a-menu>
                    </template>
                  </a-dropdown>
                </template>
                <component
                    :height="height" :is="view.components[pane.type]"
                    :id="$route.params.id" ref="editorRef" :viewId="view.state.id" :index="pane.id"
                    :table="datasheet.state.list"
                >
                </component>
              </a-tab-pane>
            </a-tabs>
          </div>
        </a-layout-content>
      </a-layout>
    </a-skeleton>
    <a-modal destroyOnClose cancelText="取消" okText="确定" v-model:open="view.state.current.edit" title="重命名视图"
             @ok="view.close(view.state.current)">
      <a-input v-focus v-model:value="view.state.current.name"/>
    </a-modal>
  </div>
</template>

<script lang="tsx">
import {
  defineComponent,
  reactive,
  onBeforeMount,
  watch,
  ref,
  nextTick,
  provide,
  defineAsyncComponent,
  onUnmounted,
  computed
} from 'vue';
import {useRoute} from "vue-router";
import {useFetch, useSortable} from '../../hooks';
import Header from '../header/index.vue';
import {OnClickOutside} from '@vueuse/components'
import {message, Modal} from 'ant-design-vue';
import {useStore} from "../../stores/table";
import {storeToRefs} from 'pinia';
import {ViewType} from '../../enum';

const Gant = defineAsyncComponent(() => import("../../components/gant/index.vue"));
const Base = defineAsyncComponent(() => import("../../components/baseTable/index.vue"));
const Chart = defineAsyncComponent(() => import("../../components/chart/index.vue"));
const Relation = defineAsyncComponent(() => import("../relation/index.vue"));

const Menu = (props, {attrs}) => {
  const {list, onClick} = props;
  return (
      <a-menu {...attrs} click={onClick}>
        {list.map(item => <a-menu-item key={item}>{item.title}</a-menu-item>)}
      </a-menu>
  )
}
export default defineComponent({
  components: {Base, Gant, Chart, Menu, Header, OnClickOutside, Relation},
  setup() {
    const store = useStore();
    const route = useRoute();
    const height = computed(() => {
      return route.query?.code ? 370 : window.innerHeight - 250
    })
    const disabled = ref(false);
    const {datasheetID, viewID} = storeToRefs(store);

    const datasheetRef = ref();
    const tabsRef = ref();
    let sortable = null;
    watch([datasheetRef], ([datasheet]) => {
      sortable = useSortable({
        el: datasheet.querySelector('.ant-tree-list-holder-inner'),
        options: {disabled: disabled.value}
      });
    });
    watch(() => disabled.value, value => {
      sortable.instance.options.disabled = value;
    })

    const editorRef = ref(null);
    const state: any = reactive({
      isFinished: false,
      title: '',
      data: {},
      menu: {
        active: '',
        list: [
          {
            title: '表格视图',
            type: ViewType.表格视图,
          },
          {
            title: '甘特视图',
            type: ViewType.甘特视图
          },
          {
            title: '图表视图',
            type: ViewType.图表视图
          },
          {
            title: '关联视图',
            type: ViewType.关联视图
          }
        ],
      }
    });
    // 数据表相关
    const datasheet = {
      state: reactive({
        current: {} as any,
        selectedKeys: [],
        list: [] as any,
        fieldNames: {
          children: 'children',
          title: 'name',
          key: 'id'
        },
        all: [],
      }),
      edit: {
        change(target, event) {
          const current = datasheet.state.list.find(item => item.id === target.id);
          current.name = event.target.value;
        },
        async close({name, id}) {
          const current = datasheet.state.list.find(item => item.id === id);
          if (name !== datasheet.state.current.name) {
            const {data} = await useFetch('/system/dwtable/edit').post({name, id}).json();
            if (data?.value) {
              message.success(`修改数据表“${name}”成功`);
            }
          }
          disabled.value = false;
          current.edit = false;
          datasheet.state.current = {};
        },
        delete({name, id}) {
          Modal.confirm({
            title: '删除数据表',
            content: `确认要删除数据表“${name}”吗？`,
            okText: '确认',
            cancelText: '取消',
            centered: true,
            okButtonProps: {
              type: 'primary',
              danger: true
            },
            async onOk() {
              const {data} = await useFetch(`/system/dwtable/remove/${id}`).get().json();
              if (data?.value) {
                message.success(`删除数据表“${name}”成功`);
                datasheet.state.list = datasheet.state.list.filter(item => item.id !== id);
                const len = datasheet.state.list.length;
                if (len > 0) {
                  const id = datasheet.state.list[len - 1].id;
                  datasheet.state.selectedKeys = [id];
                  view.get(id);
                  store.datasheetChange(id);
                }
              }
            }
          });
        }
      },
      async get() {
        let noteId: string | string[] = '';
        if (route.query?.code) {
          noteId = parent.window.location.href.split('/').at(-1);
        } else {
          noteId = route.params.id;
        }
        const {data} = await useFetch(`/system/dwtable/list?noteId=${noteId}`).get().json();
        if (data?.value) {
          datasheet.state.list = data.value.data;
          if (!route.query?.code) {
            const id = data.value?.data?.[0]?.id;
            datasheet.state.selectedKeys = [id];
            store.datasheetChange(id);
            view.get(id);
          }
          if (route.params.tableId) {
            datasheet.state.selectedKeys = [Number(route.params.tableId)];
          }
        }
      },
      async getAll() {
        const {data} = await useFetch(`/system/dwtable/list`).get().json();
        datasheet.state.all = data.value?.data || [];
        store.$state.tableList = data.value?.data || [];
        return data.value?.data || [];

      },
      async add() {
        const {data} = await useFetch('/system/dwtable/add').post({
          noteId: route.params.id,
          name: `数据表${datasheet.state.list.length + 1}`,
        }).json();
        if (data?.value) {
          datasheet.get();
          datasheet.getAll();
          message.success('数据表新增成功');
        }
      },
      select(target) {
        if (datasheetID.value === target[0]) return;
        view.state.list = [];
        view.state.current = {}
        store.viewChange(false);
        store.datasheetChange(target[0]);
        nextTick(() => {
          view.get(target[0]);
        })
      }

    }


    // 视图相关
    const view = {
      state: reactive({
        id: viewID.value || '',
        list: [],
        current: {} as any,
      }),
      async get(id) {
        if (!id) return;
        if (route.params.tableId) {
          id = route.params.tableId
        }
        store.datasheetID = id;
        const {data} = await useFetch(`/system/view/list?dwtableId=${id}`).get().json();
        if (data?.value) {
          if (data.value.rows.length === 0) {
            view.add({
              key: {
                title: '表格视图',
                type: 1,
                dwtableId: datasheetID.value
              }
            });
          }
          store.getColumns()
          store.getTableList();
          view.state.list = data.value.rows;
          view.state.id = data.value.rows[0]?.id;
          store.viewChange(view.state.id);
        }
      },
      components: {
        [ViewType.表格视图]: 'Base',
        [ViewType.甘特视图]: 'Gant',
        [ViewType.图表视图]: 'Chart',
        [ViewType.关联视图]: "Relation"
      },
      tabsChange(key) {
        view.state.id = key;
        store.viewChange(key);
      },
      async add({key}) {
        const {data} = await useFetch('/system/view/add').post({
          name: key.title,
          type: key.type,
          dwtableId: datasheetID.value
        }).json();
        if (data?.value) {
          view.get(datasheetID.value);
        }
      },
      delete(id) {
        const {name} = view.state.list.find(item => item.id === id);
        Modal.confirm({
          title: '删除视图',
          content: `确认要删除视图“${name}”吗？`,
          okText: '确认',
          cancelText: '取消',
          centered: true,
          okButtonProps: {
            type: 'primary',
            danger: true
          },
          async onOk() {
            const {data} = await useFetch(`/system/view/remove/${id}`).get().json();
            if (data?.value) {
              message.success(`删除视图${name}”成功`);
              view.state.list = view.state.list.filter(item => item.id !== id);
              const len = view.state.list.length;
              if (len > 0) {
                const id = view.state.list[len - 1].id;
                store.viewChange(id);
                view.state.id = id;
              }
            }
          }
        });
      },
      select(action, target) {
        target.edit = true;
        view.state.current = {...target, oldName: target.name};
      },
      async close(target) {
        target.edit = false;
        if (target.name !== view.state.current.oldName) {
          const {data} = await useFetch('/system/view/edit').post({
            id: target.id,
            name: target.name,
            type: target.type,
            dwtableId: datasheetID.value,
          }).json();
          if (data?.value) {
            view.get(datasheetID.value);
            message.success('重命名成功');
          }
        }
        view.state.current = {};
      }
    }

    const onEdit = (targetKey: any, action: string) => {
      if (action === 'remove') {
        editorRef.value[targetKey]?.detachEvent?.();
        view.delete(targetKey);
      }
      if (action === 'rename') {
        disabled.value = true;
        const current = datasheet.state.list.find(item => item.id === targetKey.id);
        current.edit = true;
        datasheet.state.current = {...current};
      }
    };
    const getDetail = async () => {
      const {
        data,
        isFinished
      } = await useFetch(`/system/note/${route.params.id}`).get().json();
      state.isFinished = isFinished;
      if (data?.value) {
        state.title = data.value.data?.title;
        state.data = data.value.data;
      }
    }


    onBeforeMount(async () => {
      getDetail();
      if (route.query?.code) {
        datasheet.get();
        let id = route.params.id;
        store.datasheetChange(id);
        view.get(id);
      } else {
        datasheet.get();
      }
      datasheet.getAll();
    });

    onUnmounted(() => sortable?.destroy())


    provide('datasheet', datasheet);

    return {
      state,
      view,
      height,
      tabsRef,
      onEdit,
      datasheet,
      editorRef,
      datasheetRef,
    }
  }
})
</script>

<style lang="scss" scoped>
.np-base-table {
  .ant-layout {
    height: calc(100% - 72px);
    margin: 0;
    padding: 16px;

    &.code {
      height: 100%;
      padding: 0;

      .ant-layout-content {
        padding: 0;
        margin: 0;
      }

      :deep(.ant-tabs-tabpane) {
        > div {
          height: 100%;
        }
      }

      :deep(.vxe-table) {
        height: calc(100% - 90px);
      }
    }
  }

  .ant-page-header {
    border-bottom: 1px solid var(--np-line-border);
  }

  .ant-layout-content {
    height: 100%;
    padding: 0;
    box-shadow: none;
    border-radius: 0;

    :deep(.layout-main) {
      margin: 0;
      padding: 0;
      height: 100%;
      max-height: unset;
      min-height: unset;

      .np-toolbar {
        button {
          margin: 5px;
        }
      }

      .ant-tabs-content,
      .ant-tabs {
        height: 100%;
      }

      .ant-tabs-tab:hover {
        color: unset;
      }

      .ant-tabs > .ant-tabs-nav .ant-tabs-nav-add,
      .ant-tabs > div > .ant-tabs-nav .ant-tabs-nav-add {
        background: none;
        border: none;
      }

      .ant-tabs > .ant-tabs-nav,
      .ant-tabs > div > .ant-tabs-nav {
        margin: 0;
      }
    }
  }

  .ant-layout-sider {
    padding: 0;
    box-shadow: none;
    border-radius: 0;
    margin: 0;
    height: 100%;
    margin-right: 16px;

    .datasheet-menu {
      display: flex;
      flex-direction: column;
      height: 100%;

      :deep(.datasheet-action) {
        display: flex;
        justify-content: flex-end;
        flex-direction: column;
        margin: 20px 0;

        .ant-divider {
          margin: 0;
        }
      }
    }

    .ant-btn {
      width: 90%;
      margin: 0 auto;

    }
  }
}

:deep(.datasheet-list) {
  overflow: hidden;
  flex: 1;
  padding: 10px;

  .ant-menu-item {
    text-align: center;

    .ant-menu-title-content {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

  }
}

:deep(.ant-tree) {
  .ant-tree-node-content-wrapper {
    padding: 0;
  }
}

.datasheet-item {
  overflow: hidden;

  &:hover {
    .item-action {
      opacity: 1;
      transform: translateX(0);
    }
  }

}

.item-action {
  opacity: 0; // 默认隐藏
  transform: translateX(5px);
  transition: all 0.2s;
}
</style>
