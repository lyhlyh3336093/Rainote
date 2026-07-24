<script lang="ts">
import {defineComponent, inject, ref} from 'vue';

export default defineComponent({
  name: 'np-math',
  setup(props, ctx) {
    const tableState = inject('tableState');
    const value = ref('');
    const getPopupContainer = el => el.parentNode;
    const save = () => {
      const {attrs} = ctx
      const items = [
        {
          id: attrs.props.list.find(item => item.id === attrs.props.record.id).itemId[attrs.props.columnId],
          name: attrs.props.list.find(item => item.id === attrs.props.record.id).columns[attrs.props.columnId],
          value: value.value,
          dwtId: attrs.props.datasheetID,
          columnId: attrs.props.columnId,
        }
      ]
      tableState.update({
        items,
        viewId: attrs.props.viewId,
        id: attrs.props.record.id,
      });
    }
    return {
      save,
      value,
      getPopupContainer
    }
  }
})
</script>

<template>
  <div class="np-math relative  ">
    <div class="flex flex-col items-end p-3 border border-solid rounded " style="border-color: #dee0e3">
      <a-textarea
          placeholder="数学公式只包含两个参数A和B,以及常用的数学运算符号，支持括号运算且识别运算优先级，用户不需要输入=，例：（A+B）/2,或者 （B-A）/B都是合法的"
          :rows=5 v-model:value="value"/>
      <a-button class="w-24 my-2" @click="save" type="primary">确定</a-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.np-math {
  > div {
    width: 300px;
    position: absolute;
    background: #fff;
    z-index: 99;
  }

}
</style>