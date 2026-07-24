<template>
  <div id="luckysheet"></div>

</template>

<script setup name="np-sheets">
import {onMounted} from "vue";

const options = {
  lang: 'zh',
  title: '表格',
  myFolderUrl: '/',
  allowUpdate: true,
  plugins: ['chart'],
  showinfobar: false,
  container: 'luckysheet',
  showtoolbarConfig: {
    undoRedo: true, //撤销重做，注意撤消重做是两个按钮，由这一个配置决定显示还是隐藏
    paintFormat: true, //格式刷
    currencyFormat: true, //货币格式
    percentageFormat: true, //百分比格式
    numberDecrease: true, // '减少小数位数'
    numberIncrease: true, // '增加小数位数
    moreFormats: true, // '更多格式'
    font: true, // '字体'
    fontSize: true, // '字号大小'
    bold: true, // '粗体 (Ctrl+B)'
    italic: true, // '斜体 (Ctrl+I)'
    strikethrough: true, // '删除线 (Alt+Shift+5)'
    underline: true, // '下划线 (Alt+Shift+6)'
    textColor: true, // '文本颜色'
    fillColor: true, // '单元格颜色'
    border: true, // '边框'
    mergeCell: true, // '合并单元格'
    horizontalAlignMode: true, // '水平对齐方式'
    verticalAlignMode: true, // '垂直对齐方式'
    textWrapMode: true, // '换行方式'
    textRotateMode: true, // '文本旋转方式'
    image: true, // '插入图片'
    link: true, // '插入链接'
    chart: true, // '图表'（图标隐藏，但是如果配置了chart插件，右击仍然可以新建图表）
    postil: true, //'批注'
    pivotTable: true,  //'数据透视表'
    function: true, // '公式'
    frozenMode: true, // '冻结方式'
    sortAndFilter: true, // '排序和筛选'
    conditionalFormat: true, // '条件格式'
    dataVerification: true, // '数据验证'
    splitColumn: true, // '分列'
    screenshot: true, // '截图'
    findAndReplace: true, // '查找替换'
    protection: true, // '工作表保护'
    print: false, // '打印'
  },
  hook:{
  }
}
onMounted(() => {
  window.luckysheet.create(options);
})
</script>
<script>
export default {
  name: "np-sheets"
}

</script>

<style scoped>
#luckysheet {
  width: 100%;
  height: 100%;
}
</style>