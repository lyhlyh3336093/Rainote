<template>
  <a-table :scroll="{ y: 800 }" :dataSource="data" :columns="columns" :show-expand-column="false">
    <template #bodyCell="{ column, record, index }">
      <template v-if="column.key === 'action'">
        <div class="flex">
          <a-button @click="onClick(record, 1)" type="link">单向链接</a-button>
          <a-button @click="onClick(record, 2)" type="link">双向链接</a-button>
          <a-button @click="onClick({ ...record, index }, 3)" type="link">修改</a-button>
          <a-button @click="onClick(index, 4)" type="link" danger>删除</a-button>
        </div>
      </template>
    </template>
  </a-table>

  <a-modal wrap-class-name="w-screen" centered v-model:open="state.visible" :title="state.current.title" :footer="null"
    destroyOnClose>
    <iframe :src="state.current.value"></iframe>
  </a-modal>
  <a-modal centered v-model:open="edit.visible" :title="edit.current.title" :footer="null" width="500px" destroyOnClose>
    <a-form :model="formState" @finish="onFinish">
      <a-form-item label="链接" name="link" :rules="[{ required: true, message: '请输入链接' }]">
        <a-input v-focus v-model:value="formState.link" />
      </a-form-item>

      <a-form-item :wrapper-col="{ span: 24 }" class="flex text-right">
        <a-button class="mr-2" @click="onClose">取消</a-button>
        <a-button type="primary" html-type="submit">确认</a-button>
      </a-form-item>
    </a-form>
  </a-modal>
</template>
<script lang="tsx">
import { Modal } from 'ant-design-vue';
import { defineComponent, reactive } from 'vue';

export default defineComponent({
  emits: ['visible', 'change-link'],
  name: 'link-list',
  props: ['data', 'target'],
  setup(props, ctx) {
    const { target } = props;
    const state = reactive({
      visible: false,
      current: {} as any,
    });
    const edit = reactive({
      visible: false,
      current: {} as any,
    });
    const formState = reactive({
      link: ''
    })
    const action = {
      1: (item) => {
        window.open(item.value, '_blank');
      },
      2: () => {
        state.visible = true;
      },
      3: (item) => {
        edit.current = item;
        edit.visible = true;
        formState.link = item.value;
      },
      4: (index) => {
        Modal.confirm({
          title: '确认删除？',
          okText: '确认',
          cancelText: '取消',
          centered: true,
          onOk: () => {
            let links = JSON.parse(target.getAttribute("link")) || [];
            const newLink = links.filter((v, i) => i !== index);
            links = JSON.stringify(newLink);
            if (newLink.length === 0) {
              target.removeAttribute('link');
            } else {
              target.setAttribute('link', links);
            }
            ctx.emit('change-link', newLink);
          }
        })

      }
    }
    const onClick = (item, type) => {
      if (type <= 2) {
        state.current = item;
        if (!/http/ig.test(item.value)) {
          item.value = 'http://' + item.value
        }
      }
      action?.[type]?.(item);
    }
    const remove = item => {
      console.log(item);
    }
    const columns = [
      {
        title: '选词',
        dataIndex: 'title',
        key: 'title',
        width: 150,
        ellipsis: true,
      },
      {
        title: '地址',
        dataIndex: 'value',
        key: 'value',
        width: 300,
        ellipsis: true,
      },
      {
        title: '描述',
        dataIndex: 'desc',
        key: 'desc',
        width: 150,
        ellipsis: true,
      },
      {
        title: '操作',
        key: 'action',
        align: 'center',
      },
    ];
    const onFinish = (values) => {
      const { link } = values;
      if (link === edit.current.value) return;
      const { index } = edit.current;
      let links = JSON.parse(target.getAttribute("link"));
      const newLink = links.map((v, i) => {
        if (i === index) {
          v.value = link;
        }
        return v;
      });
      links = JSON.stringify(newLink);
      target.setAttribute('link', links);
      ctx.emit('change-link', newLink, true);
      edit.visible = false;
    }
    const onClose = () => {
      edit.visible = false;
    }
    return { onClick, state, remove, columns, edit, formState, onFinish, onClose }
  },
})
</script>
<style scoped lang="scss">
:deep(.ant-table-tbody) {
  tr>td {
    border: none !important;
  }
}
</style>