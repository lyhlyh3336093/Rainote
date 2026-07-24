<template>
  <a-tabs
    v-model:activeKey="state.activeKey"
    tab-position="left"
    :style="{ height: '300px' }"
  >
    <a-tab-pane v-for="tab in state.tabs" :key="tab.title">
      <template #tab>
        <component :is="tab.icon" :key="tab.title"></component>
        {{ tab.title }}
      </template>
      <div v-if="tab.title === '信息与数据'">
        <div>
          <div class="mb-5">
            <div>创建信息</div>
            <div class="flex justify-around">
              <div class="flex-1 bg-slate-100 p-2 mr-3 rounded">
                所有者 {{ state.data.createName }}
              </div>
              <div class="flex-1 bg-slate-100 p-2 rounded">
                创建时间
                {{ dayjs(state.data.createDate).format("YYYY-MM-DD hh:mm:ss") }}
              </div>
            </div>
          </div>
          <div class="mb-5">
            <div>字数统计</div>
            <div class="flex justify-around">
              <div
                class="flex-1 bg-slate-100 p-2 mr-3 rounded flex items-center"
              >
                总字数 <span class="font-bold text-xl ml-2">0</span>
              </div>
              <div class="flex-1 bg-slate-100 p-2 rounded flex items-center">
                总字符数 <span class="font-bold text-xl ml-2">0</span>
              </div>
            </div>
          </div>
          <div>
            <div>互动统计</div>
            <div class="flex justify-around">
              <div
                class="flex-1 bg-slate-100 p-2 mr-3 rounded h-32 flex justify-center items-center flex-col"
              >
                访问人数 <span class="font-bold text-xl">0</span>
              </div>
              <div
                class="flex-1 bg-slate-100 p-2 mr-3 rounded h-32 flex justify-center items-center flex-col"
              >
                访问次数 <span class="font-bold text-xl">0</span>
              </div>
              <div
                class="flex-1 bg-slate-100 p-2 mr-3 rounded h-32 flex justify-center items-center flex-col"
              >
                点赞总数 <span class="font-bold text-xl">0</span>
              </div>
              <div
                class="flex-1 bg-slate-100 p-2 rounded h-32 flex justify-center items-center flex-col"
              >
                评论总数 <span class="font-bold text-xl">0</span>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div v-else>{{ tab.title }}</div>
    </a-tab-pane>
  </a-tabs>
</template>
<script lang="tsx">
import { defineComponent, reactive } from "vue";
import { useRoute } from "vue-router";
import { useFetch } from "../../hooks";
import dayjs from "dayjs";

export default defineComponent({
  setup() {
    const route = useRoute();
    const state = reactive({
      data: {} as any,
      activeKey: "信息与数据",
      tabs: [
        {
          title: "信息与数据",
          icon: <container-outlined />,
        },
        {
          title: "访问记录",
          icon: <eye-outlined />,
        },
        {
          title: "操作记录",
          icon: <history-outlined />,
        },
      ],
    });
    const fetch = async () => {
      const { data } = await useFetch(`/system/meta/info/${route.params.id}`)
        .get()
        .json();
      if (data?.value) {
        state.data = data.value.data;
      }
    };
    fetch();
    return {
      dayjs,
      state,
    };
  },
});
</script>
