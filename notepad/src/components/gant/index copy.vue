<template>
  <div class="gant-chart-box">
    <div :ref="setRef" class="gant-chart-canvas"></div>
  </div>
</template>

<script lang="tsx">
import { gantt } from "dhtmlx-gantt";
import "dhtmlx-gantt/codebase/dhtmlxgantt.css";
// import 'dhtmlx-gantt/codebase/skins/dhtmlxgantt_terrace.css'
import {
  ref,
  onMounted,
  defineComponent,
  onUnmounted,
  nextTick,
  watch,
} from "vue";
import { useStore } from "../../stores/table";
import { storeToRefs } from "pinia";
import { useRoute } from "vue-router";
import dayjs from "dayjs";
import { useFetch } from "../../hooks";
import { message } from "ant-design-vue";
export default defineComponent({
  props: ["id", "index", "activeKey"],
  name: "GantChart",
  setup(props) {
    const store = useStore();
    const route = useRoute();
    const { table, columns, datasheetID } = storeToRefs(store);
    const { id, index, activeKey } = props;
    const gantRefs = {
      [`gantRefs-${id}`]: ref(null),
    };

    let onLightboxSave = null; 
    let onBeforeTaskAdd = null;
    let onAfterTaskDelete = null;
    let onAfterTaskUpdate = null;
    const updateTask = async (task, taskStart) => {
      const { data } = await useFetch("/system/record/update")
        .post({
          items: columns.value.map((item) => {
            const id = table.value.list.find((v) => v.id === task.id)["itemId"][
              item.id
            ];
            const value = table.value.list.find((v) => v.id === task.id)[
              "tableData"
            ][item.id];
            return {
              columnId:item.id,
              id,
              name: item.name,
              value:
                item.type === 1
                  ? task.text
                  : item.type === 5
                  ? dayjs(taskStart).format("YYYY-MM-DD")
                  : value,
              dwtId: datasheetID.value,
            };
          }),
          viewId: store.viewID,
          id: table.value.list[0].id,
        })
        .json();
      if (data?.value) {
        store.getTableList();
      }
    };
    onMounted(() => {
      if (table.value.list.length === 0) {
        store.getColumns();
        store.getTableList();
      }
      gantt.clearAll();
      const hourToStr = gantt.date.date_to_str("%H:%i");
      // const hourRangeFormat = function (step) {
      //   return function (date) {
      //     const intervalEnd = new Date(gantt.date.add(date, step, 'hour') - 1)
      //     return hourToStr(date) + ' - ' + hourToStr(intervalEnd)
      //   }
      // }
      // const zoomConfig = {
      //   minColumnWidth: 80,
      //   maxColumnWidth: 150,
      //   levels: [
      //     [
      //       {unit: 'month', format: '%M %Y', step: 1},
      //       {
      //         unit: 'week',
      //         step: 1,
      //         format: function (date) {
      //           const dateToStr = gantt.date.date_to_str('%d %M')
      //           const endDate = gantt.date.add(date, -6, 'day')
      //           const weekNum = gantt.date.date_to_str('%W')(date)
      //           return 'Week #' + weekNum + ', ' + dateToStr(date) + ' - ' + dateToStr(endDate)
      //         }
      //       }
      //     ],
      //     [
      //       {unit: 'month', format: '%M %Y', step: 1},
      //       {unit: 'day', format: '%d %M', step: 1}
      //     ],
      //     [
      //       {unit: 'day', format: '%d %M', step: 1},
      //       {unit: 'hour', format: hourRangeFormat(12), step: 12}
      //     ],
      //     [
      //       {unit: 'day', format: '%d %M', step: 1},
      //       {unit: 'hour', format: hourRangeFormat(6), step: 6}
      //     ],
      //     [
      //       {unit: 'day', format: '%d %M', step: 1},
      //       {unit: 'hour', format: '%H:%i', step: 1}
      //     ]
      //   ],
      //   useKey: 'ctrlKey',
      //   trigger: 'wheel',
      //   element: function () {
      //     return gantt.$root.querySelector('.gantt_task')
      //   }
      // }
      //
      // gantt.ext.zoom.init(zoomConfig)
      gantt.config.date_format = "%Y-%m-%d";
      gantt.i18n.setLocale("cn");
      gantt.templates.tooltip_text = function (start, end, task) {
        return "<b>任务名称:</b> " + task.text;
      };
      gantt.plugins({
        // click_drag: true,
        // auto_scheduling: true,
        tooltip: true,
        // multiselect: true,
        // quick_info: true,
        // keyboard_navigation: true,
        // undo: true,
        // critical_path: true,
        drag_timeline: true,
        // fullscreen: true,
        marker: true,
      });
      gantt.config.drag_project = true;
      // 设置显示的文本
      gantt.locale.labels.section_description = "任务名称";
      gantt.locale.labels.section_time = "预计开始日期-结束时间";

      // 客制化 列
      gantt.config.columns = [
        {
          name: "text",
          label: "任务名称",
          tree: true,
          width: 150,
          align: "left",
        },
        {
          name: "taskType",
          label: "任务类型",
          width: 80,
          align: "center",
          template: function (obj) {
            if (obj.taskType == "project") {
              return "任务";
            } else {
              return "详细任务";
            }
          },
        },
        {
          name: "start_date",
          label: "预计开始时间",
          width: 80,
          align: "center",
        },
        {
          name: "end_date",
          label: "预计结束时间",
          width: 80,
          align: "center",
        },
        { name: "duration", label: "预计时长", width: 80, align: "center" },
        { name: "progress", label: "进度", width: 50, align: "center" },
        // { name: "add", label: "", width: 44 },
      ];
      // 点击保存后触发的事件
      onLightboxSave = gantt.attachEvent(
        "onLightboxSave",
        (id, task, is_new) => {
          const taskStart = task.start_date;
          const taskEnd = task.end_date;
          const scaleStart = gantt.config.start_date;
          const scaleEnd = gantt.config.end_date;
          if (scaleStart > taskEnd || scaleEnd < taskStart) {
            gantt.config.end_date = new Date(
              Math.max(taskEnd.valueOf(), scaleEnd.valueOf())
            );
            gantt.config.start_date = new Date(
              Math.min(taskStart.valueOf(), scaleStart.valueOf())
            );
            gantt.render();
          }
          updateTask(task, taskStart);
          return true;
        },
        {}
      );
      onAfterTaskDelete = gantt.attachEvent(
        "onAfterTaskDelete",
        async (id, item) => {
          const { data } = await useFetch(`/system/record/remove/${id}`)
            .get()
            .json();
          if (data?.value) {
            store.getTableList();
            message.success("删除成功");
          }
        },
        {}
      );
      onBeforeTaskAdd = gantt.attachEvent(
        "onBeforeTaskAdd",
        (id, item) => {
          if (index === activeKey) {
            // store.add({
            //   key: index,
            //   id: props.id,
            //   table: route.query.table,
            //   item
            // });
          }
        },
        {}
      );
      onAfterTaskUpdate = gantt.attachEvent(
        "onAfterTaskUpdate",
        (id, item) => {
          updateTask(item, item.start_date);
        },
        {}
      );
      gantt.config.scale_unit = "year";
      gantt.config.step = 1;
      gantt.config.date_scale = "%Y";
      gantt.config.scale_height = 60;
      gantt.config.fit_tasks = true;
      gantt.config.task_height = 28;
      //时间轴图表中，甘特图的高度
      gantt.config.row_height = 36;

      function setScaleConfig(level) {
        switch (level) {
          case "day":
            gantt.config.scales = [{ unit: "day", step: 1, format: "%d %M" }];
            gantt.config.scale_height = 27;
            break;
          case "week":
            const weekScaleTemplate = (date) => {
              const dateToStr = gantt.date.date_to_str("%d %M");
              const endDate = gantt.date.add(
                gantt.date.add(date, 1, "week"),
                -1,
                "day"
              );
              return dateToStr(date) + " - " + dateToStr(endDate);
            };
            gantt.config.scales = [
              { unit: "week", step: 1, format: weekScaleTemplate },
            ];
            gantt.config.scale_height = 27;
            break;
          case "month":
            gantt.config.scales = [
              { unit: "month", step: 1, format: "%F, %Y" },
            ];
            gantt.config.scale_height = 27;
            break;
          case "year":
            gantt.config.scales = [{ unit: "year", step: 1, format: "%Y" }];
            gantt.config.scale_height = 27;
            break;
        }
      }

      gantt.config.show_task_cells = true;
      gantt.templates.timeline_cell_class = function (task, date) {
        if (date.getDay() == 0 || date.getDay() == 6) {
          return "weekend";
        }
      };
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
      gantt.config.start_date = new Date(2022, 1, 1);
      gantt.config.end_date = new Date(2025, 1, 1);
      // 初始化
      gantt.init(gantRefs[id]);

      // 甘特图数据
      // gantt.parse({
      //   tasks: [
      //     {
      //       text: "r这是任务名称",
      //       taskType: "这是任务类型",
      //       start_date: "2022-04-19",
      //       // finishDate: "2022-40-20",
      //       // duration: "1",
      //       // progress: 0,
      //     },
      //   ],
      //   link: [],
      // });
      parse();
    });
    const detachEvent = () => {
      nextTick(() => {
        [
          onLightboxSave,
          onBeforeTaskAdd,
          onAfterTaskUpdate,
          onAfterTaskDelete,
        ].forEach((action) => {
          gantt.detachEvent(action);
        });
      });
    };
    onUnmounted(() => {
      detachEvent();
    });
    const setRef = (el) => (gantRefs[id] = el);

    const parse = () => {
      const dateId =
        table.value.columnsId[
          table.value.columnsType.findIndex((type) => type === 5)
        ];
      const tasks = table.value.tableData.map((item) => {
        const text = item[table.value.columnsId[0]];
        let start_date = item[dateId] || dayjs(new Date()).format("YYYY-MM-DD");
        return {
          id: item.id,
          text,
          start_date,
        };
      });
      gantt.parse({
        tasks,
        link: [],
      });
    };

    watch(
      () => table.value.tableData,
      () => {
        parse();
      }
    );
    return {
      setRef,
      gantRefs,
      detachEvent,
    };
  },
});
</script>

<style lang="scss" scoped>
.gant-chart-box,
.gant-chart-canvas {
  width: 100%;
  height: calc(100% - 20px);
}
</style>