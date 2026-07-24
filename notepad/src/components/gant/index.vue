<template>
  <div class="gant-chart-box">
    <div ref="ganttContainer" class="gant-chart-canvas"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from "vue";
import { gantt } from "dhtmlx-gantt";
import "dhtmlx-gantt/codebase/dhtmlxgantt.css";
import { useStore } from "../../stores/table";
import { storeToRefs } from "pinia";
import dayjs from "dayjs";
import { useFetch } from "../../hooks";
import { message } from "ant-design-vue";

// 定义 Props
const props = defineProps<{
  id: string | number;
  index: number;
  activeKey: number;
}>();

const store = useStore();
const { table, columns, datasheetID, viewID } = storeToRefs(store);
const ganttContainer = ref<HTMLElement | null>(null);
const eventIds: any[] = []; // 存储附加事件的 ID 用于销毁

/**
 * 同步后端任务更新
 * @param task 甘特图返回的 task 对象
 */
const syncTaskToBackend = async (task: any) => {
  try {
    // 找到对应的原始数据行
    const originalRow = table.value.list.find((v: any) => v.id === task.id);
    if (!originalRow) return;

    const payload = {
      items: columns.value.map((col: any) => {
        const itemId = originalRow.itemId[col.id];
        const oldValue = originalRow.tableData[col.id];

        // 逻辑映射：如果是第一列(通常是名称)映射到 text，如果是日期列(type 5)映射到 start_date
        let value = oldValue;
        if (col.type === 1) value = task.text;
        else if (col.type === 5) value = dayjs(task.start_date).format("YYYY-MM-DD");

        return {
          columnId: col.id,
          id: itemId,
          name: col.name,
          value: value,
          dwtId: datasheetID.value,
        };
      }),
      viewId: viewID.value,
      id: originalRow.id,
    };

    const { data } = await useFetch("/system/record/update").post(payload).json();
    if (data?.value) {
      store.getTableList();
    }
  } catch (error) {
    message.error("更新失败");
  }
};

/**
 * 初始化甘特图配置
 */
const initGanttConfig = () => {
  gantt.clearAll();
  gantt.i18n.setLocale("cn");
  gantt.config.date_format = "%Y-%m-%d";
  gantt.config.drag_project = true;
  gantt.config.row_height = 36;
  gantt.config.task_height = 28;
  gantt.config.fit_tasks = true;

  // 插件启用
  gantt.plugins({
    tooltip: true,
    drag_timeline: true,
    marker: true,
  });

  // 列配置
  gantt.config.columns = [
    { name: "text", label: "任务名称", tree: true, width: 180, align: "left" },
    {
      name: "start_date",
      label: "开始时间",
      width: 100,
      align: "center",
      template: (task: any) => dayjs(task.start_date).format("YYYY-MM-DD")
    },
    { name: "duration", label: "时长", width: 60, align: "center" },
    {
      name: "progress",
      label: "进度",
      width: 60,
      align: "center",
      template: (task: any) => `${Math.round((task.progress || 0) * 100)}%`
    },
  ];

  // 时间轴刻度配置 (双层刻度)
  gantt.config.scales = [
    { unit: "year", step: 1, format: "%Y" },
    { unit: "month", step: 1, format: "%M" }
  ];

  //指定第二个时间刻度
  gantt.config.subscales = [
    {
      unit: "day",
      step: 1,
      date: `<span>%M%d日</span>`,
      css: (date) => {
        if (date.getDay() == 0 || date.getDay() == 6) {
          return "weekend";
        }
      },
    },
  ];

  gantt.i18n.setLocale({
    labels: {
      gantt_save_btn: "保存",
      gantt_cancel_btn: "取消",
      gantt_delete_btn: "删除",
    },
  });

  // 周末高亮样式
  gantt.templates.timeline_cell_class = (task, date) => {
    const day = date.getDay();
    return day === 0 || day === 6 ? "weekend" : "";
  };
};

/**
 * 绑定甘特图事件
 */
const initGanttEvents = () => {
  // 任务保存/编辑更新
  eventIds.push(gantt.attachEvent("onAfterTaskUpdate", (id, item) => {
    syncTaskToBackend(item);
    return true;
  }));

  // 弹窗保存
  eventIds.push(gantt.attachEvent("onLightboxSave", (id, task) => {
    syncTaskToBackend(task);
    return true;
  }));

  // 删除任务
  eventIds.push(gantt.attachEvent("onAfterTaskDelete", async (id) => {
    const { data } = await useFetch(`/system/record/remove/${id}`).get().json();
    if (data?.value) {
      store.getTableList();
      message.success("删除成功");
    }
  }));
};

/**
 * 解析 Store 数据并同步到甘特图
 */
const parseData = () => {
  if (!table.value.tableData || table.value.tableData.length === 0) return;

  // 查找日期列 ID (假设 type 5 是日期)
  const dateColIndex = table.value.columnsType.findIndex((type: number) => type === 5);
  const dateId = table.value.columnsId[dateColIndex];

  const tasks = table.value.tableData.map((item: any) => {
    const text = item[table.value.columnsId[0]] || "无标题";
    const rawDate = item[dateId];
    // 容错处理日期
    const start_date = rawDate ? dayjs(rawDate).toDate() : new Date();

    return {
      id: item.id,
      text,
      start_date,
      duration: item.duration || 1, // 建议从后端取或默认1
      progress: item.progress || 0,
      open: true
    };
  });

  gantt.parse({ tasks, links: [] });
};

// --- 生命周期 ---

onMounted(async () => {
  // 确保数据就绪
  if (table.value.list.length === 0) {
    await store.getColumns();
    await store.getTableList();
  }

  // 初始化配置
  initGanttConfig();
  initGanttEvents();

  // 挂载 DOM
  if (ganttContainer.value) {
    gantt.init(ganttContainer.value);
    parseData();
  }
});

onUnmounted(() => {
  // 移除所有附加事件，防止内存泄漏和逻辑重叠
  eventIds.forEach(id => gantt.detachEvent(id));
  gantt.clearAll();
});

// 监听数据变化刷新图表
watch(
  () => table.value.tableData,
  () => {
    parseData();
  },
  { deep: true }
);

// 监听 Tab 切换
watch(
  () => props.activeKey,
  (newVal) => {
    if (newVal === props.index) {
      nextTick(() => gantt.render());
    }
  }
);
</script>

<style lang="scss" scoped>
.gant-chart-box {
  width: 100%;
  height: calc(100% - 20px);
  background: #fff;

  .gant-chart-canvas {
    width: 100%;
    height: 100%;

    // 内部样式覆盖
    :deep(.weekend) {
      background: #f4f7f6 !important;
    }
  }
}
</style>