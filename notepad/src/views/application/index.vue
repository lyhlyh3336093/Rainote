<script setup lang="ts">
import { ref, onMounted, reactive } from "vue";
import useFetch from "../../hooks/useFetch.ts";
import Copyright from "@/components/copyright/index.vue";
import { useUserStore } from "../../stores/user/index.ts";
import { storeToRefs } from "pinia";
import { message } from "ant-design-vue";
const { userInfo } = storeToRefs(useUserStore());

const appList = ref([]);
const visible = ref(false);
const formState = reactive({
  name: null,
  logo: null,
  company: null,
  description: null,
  link: null,
  sort: null,
  weight: null,
});
const formRef = ref();
const close = () => {
  formRef.value.resetFields();
  visible.value = false;
};
const onFinish = async (values) => {
  const { data } = await useFetch("system/app/add").post({
    ...values,
    creater: userInfo.value.user.userId,
  }).json;
  message.success("应用注册成功");
  close();
  setTimeout(() => {
    fetchAppList();
  }, 500);
};
const onJump = (item) => {
  const url =
    item.link.startsWith("http://") || item.link.startsWith("https://")
      ? item.link
      : `http://${item.link}`;
  window.open(url);
};
const type = ref("table");

const fetchAppList = async () => {
  const { data, error } = await useFetch("/system/app/list").json(); // 替换为实际 API 地址
  if (error.value) {
    console.error("获取列表失败:", error.value);
  } else {
    appList.value =
      data.value?.data.map((item) => {
        return {
          ...item,
          logo: "https://i.thsi.cn/images/home/v3/logo_v2.png",
        };
      }) || [];
  }
};
// 在组件加载时调用
onMounted(() => {
  fetchAppList();
});
</script>

<template>
  <div class="app overflow-auto">
    <div class="flex justify-end absolute top-5 right-6">
      <a-button type="primary" @click="visible = true">
        <plus-outlined />
        注册应用
      </a-button>
      <appstore-outlined
        class="cursor-pointer"
        style="font-size: 30px"
        @click="type = 'list'"
        v-show="type === 'table'"
      />
      <bars-outlined
        class="cursor-pointer"
        style="font-size: 30px"
        @click="type = 'table'"
        v-show="type === 'list'"
      />
    </div>
    <div style="padding: 20px; padding-top: 60px" v-show="type === 'list'">
      <a-list
        class="demo-loadmore-list"
        item-layout="horizontal"
        :data-source="appList"
      >
        <template #renderItem="{ item }">
          <a-list-item>
            <a-skeleton avatar :title="false" :loading="!!item.loading" active>
              <a-list-item-meta :description="item.link">
                <template #title>
                  <a-tooltip>
                    <template #title>{{ item.description }}</template>
                    <span class="cursor-pointer">
                      {{ item.name }}
                    </span>
                  </a-tooltip>
                </template>
                <template #avatar>
                  <a-avatar
                    class="cursor-pointer"
                    @click="onJump(item)"
                    :size="50"
                    shape="square"
                    :src="item.image"
                  />
                </template>
              </a-list-item-meta>
            </a-skeleton>
          </a-list-item>
        </template>
      </a-list>
    </div>
    <div
      v-show="type === 'table'"
      style="padding: 20px; padding-top: 60px; height: 100%"
    >
      <a-row :gutter="16">
        <a-col class="my-2" :span="3" v-for="(item, key) in appList" :key="key">
          <a-card hoverable bordered>
            <template #cover v-if="item.logo">
              <img
                @click="onJump(item)"
                style="
                  height: 200px;
                  object-fit: contain;
                  border-bottom: 1px solid #d9d9d9;
                  padding: 10px;
                "
                :alt="item.title"
                :src="item.logo"
              />
            </template>
            <a-tooltip placement="topLeft">
              <template #title>{{ item.description }}</template>
              <p>{{ item.name }}</p>
            </a-tooltip>
            <p>{{ item.link }}</p>
          </a-card>
        </a-col>
      </a-row>
    </div>
    <a-modal
      destroyOnClose
      :footer="null"
      v-model:open="visible"
      title="注册应用"
      width="500px"
    >
      <a-form
        ref="formRef"
        :model="formState"
        name="basic"
        :label-col="{ span: 6 }"
        :wrapper-col="{ span: 16 }"
        autocomplete="off"
        @finish="onFinish"
      >
        <a-form-item
          :rules="[{ required: true, message: '请输入应用名称' }]"
          label="应用名称"
          name="name"
        >
          <a-input v-model:value="formState.name" />
        </a-form-item>

        <a-form-item label="域名" name="link">
          <a-input v-model:value="formState.link" />
        </a-form-item>

        <a-form-item label="描述" name="description">
          <a-input v-model:value="formState.description" />
        </a-form-item>

        <a-form-item label="排序" name="'sort">
          <a-input-number
            style="width: 100% !important"
            v-model:value="formState.sort"
          />
        </a-form-item>
        <a-form-item label="权重" name="weight">
          <a-input-number
            style="width: 100% !important"
            v-model:value="formState.weight"
          />
        </a-form-item>
        <a-form-item label="应用所属公司" name="company">
          <a-input v-model:value="formState.company" />
        </a-form-item>
        <a-form-item label="logo图" name="logo">
          <a-input v-model:value="formState.logo" />
        </a-form-item>
        <a-form-item>
          <div class="flex justify-end">
            <a-button class="mr-10" type="primary" html-type="submit"
              >确认</a-button
            >
            <a-button @click="close">取消</a-button>
          </div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
  <Copyright />
</template>

<style scoped lang="scss">
.app {
  height: calc(100% - 50px) !important;
}

.ant-avatar {
  :deep(img) {
    object-fit: contain;
  }
}

.ant-card {
  border-radius: 10px;
}

.ant-list-split .ant-list-item {
  border-color: #d9d9d9;
}
</style>
