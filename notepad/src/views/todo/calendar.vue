<template>
  <FullCalendar class="h-full" :options="calendarOptions" ref="calendarRef">
    <template #eventContent="arg">
      <a-dropdown
        :trigger="['click', 'contextmenu']"
      >
        <div @contextmenu.stop class="date">
          {{ arg.event.title }}
        </div>
        <template #overlay>
          <a-menu @click="(e) => onMenuClick(e, arg)">
            <a-menu-item v-for="item in ['删除记录', '修改记录']" :key="item">
              <div class="flex items-center">
                <div class="mr-2 flex items-center">
                  <delete-outlined v-if="item === '删除记录'" />
                  <edit-outlined v-else />
                </div>
                {{ item }}
              </div>
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
    </template>
  </FullCalendar>
  <a-modal
    v-model:open="modalState.visible"
    :title="modalState.title"
    :footer="null"
    width="50%"
    @afterClose="cancel"
    @cancel="cancel"
    destroy-on-close
  >
    <AddOrUpdate
      @finish="getData()"
      :row="modalState.row"
      :type="modalState.type"
      :date="modalState.date"
      v-model:visible="modalState.visible"
    />
  </a-modal>
</template>
<script lang="ts" setup>
import { inject, reactive, watch, ref } from "vue";
import FullCalendar from "@fullcalendar/vue3";
import dayGridPlugin from "@fullcalendar/daygrid";
import timeGridPlugin from "@fullcalendar/timegrid";
import interactionPlugin from "@fullcalendar/interaction";
import listPlugin from "@fullcalendar/list";
import multiMonthPlugin from "@fullcalendar/multimonth";
import bootstrap5Plugin from "@fullcalendar/bootstrap5";
import AddOrUpdate from "./addOrUpdate.vue";
import zh from "@fullcalendar/core/locales/zh-cn";
import { Modal } from "ant-design-vue";
import { useFetch } from "@/hooks";

const calendarRef = ref();
const state = inject("state");
const getData = inject("getData");
const modalState = reactive({
  visible: false,
  title: "新增事项",
  type: "add",
  row: {},
  date: null,
});
const generateRandomColor = () => {
  const hex = Math.floor(Math.random() * 0xffffff).toString(16);
  return "#" + ("000000" + hex).slice(-6);
};
const getPopupContainer = (el) => {
  return el.parentNode.parentNode.parentNode.parentNode;
};
const eventDrop = (info) => {
  console.log(info);
};
const cancel = () => {
  modalState.visible = false;
  modalState.type = "add";
  modalState.title = "新增记录";
};

const eventClick = (info) => {
  info.jsEvent.preventDefault();
  if (info.event.url) {
    window.open(info.event.url);
  }
  console.log(info);
};
const datesSet = (info) => {
  const startYear = info.start.getFullYear();
  const startMonth = info.start.getMonth() + 1; // 月份从0开始，需要加1
  const startDay = info.start.getDate();

  const endYear = info.end.getFullYear();
  const endMonth = info.end.getMonth() + 1; // 月份从0开始，需要加1
  const endDay = info.end.getDate();
};

const dayCellContent = (date) => {
  const div = document.createElement("div");
  div.classList.add(...["flex", "justify-between"]);
  const title = document.createElement("p");
  title.innerText = date.dayNumberText;
  div.appendChild(title);
  const add = document.createElement("p");
  add.innerText = "+";
  add.classList.add("add-task");
  add.addEventListener("click", (e) => {
    e.preventDefault();
    e.stopPropagation();
    modalState.type = "add";
    modalState.title = "新增事项";
    modalState.visible = true;
    modalState.date = date;
  });
  div.appendChild(add);
  let arrayOfDomNodes = [div];

  return { domNodes: arrayOfDomNodes };
};

const calendarOptions = reactive({
  plugins: [
    dayGridPlugin,
    timeGridPlugin,
    interactionPlugin,
    listPlugin,
    multiMonthPlugin,
    bootstrap5Plugin,
  ],
  initialView: "dayGridMonth",
  locale: zh,
  events: [],
  themeSystem: "bootstrap5",
  headerToolbar: {
    left: "",
    center: "title",
    right: "today,listWeek prev,next multiMonthYear,dayGridMonth,timeGridWeek",
  },
  buttonText: {
    prev: "上月",
    next: "下月",
    today: "今天",
    dayGridMonth: "月",
    timeGridWeek: "周",
    timeGridDay: "日",
  },
  editable: true,
  selectable: false,
  eventStartEditable: true,
  eventDurationEditable: true,
  eventDrop,
  eventClick,
  dayCellContent,
  datesSet,
});

watch(
  () => state,
  (val) => {
    calendarOptions.events = val.list.map((item) => {
      return {
        ...item,
        title: item.name,
        start: item.startTime,
        end: item.endTime,
        color: generateRandomColor(),
      };
    });
  },
  {
    deep: true,
  }
);
const onMenuClick = (key, arg) => {
  modalState.title = key.key;
  if (key.key === "删除记录") {
    Modal.confirm({
      centered: true,
      title: "删除",
      content: "你确定要删除该待办事项吗？",
      okText: "删除",
      cancelText: "取消",
      async onOk() {
        await useFetch(`system/task/remove/${arg.event.id}`);
        getData();
      },
    });
  } else {
    modalState.type = "upadte";
    modalState.row = state.list.find((item) => `${item.id}` === arg.event.id);
    modalState.visible = true;
  }
};
</script>
<style scoped lang="scss">
</style>
