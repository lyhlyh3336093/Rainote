<template>
  <div class='np-toolbar'>
    <template v-for="item in toolbar" :key="item.title">
      <template v-if="item.component">
        <a-dropdown :trigger="['click']" >
          <a-button type="text">
            <component :is="item.icon"></component>
            {{ item.title }}
          </a-button>
          <template #overlay>
            <component :is="item.component" @action="e=>onClick(item,e)" />
          </template>
        </a-dropdown>
      </template>
      <template v-else>
        <a-button :type="item.title === '添加记录' ? 'primary' : 'text'" @click="onClick(item)">
          <component :is="item.icon"></component>
          {{ item.title }}
        </a-button>
        <slot></slot>
      </template>
    </template>
    
  </div>
</template>

<script lang="tsx">
import {defineComponent} from 'vue';
import RowHeight from '../rowHeight/index.vue';
import Group from '../group/index.vue';
import Field from '../field/index.vue';

export default defineComponent({
  emits: ['click'],
  name: "np-toolbar",
  components: {RowHeight, Group, Field},
  setup(props, ctx) {
    const toolbar = [
      {
        title: '添加记录',
        icon: <plus-circle-outlined/>,
      },
      {
        title: '字段配置',
        component: 'Field',
        icon: <setting-outlined/>,
      },
      // {
      //   title: '筛选',
      //   icon: <filter-outlined/>
      // },
      {
        title: '分组',
        component: 'Group',
        icon: <group-outlined/>,
      },
      // {
      //   title: '排序',
      //   icon: <sort-ascending-outlined/>,
      // },
      {
        title: '行高',
        component: 'RowHeight',
        icon: <column-height-outlined/>,
      },
      // {
      //   title: '提醒',
      //   icon: <carry-out-outlined/>,
      // },
      // {
      //   title: '生成表单',
      //   icon: <file-done-outlined/>
      // }
    ];
    const onClick = (item, key) => {
      ctx.emit('click', item, key);
    }
    return {
      toolbar,
      onClick
    }
  }
})
</script>

<style scoped>

</style>