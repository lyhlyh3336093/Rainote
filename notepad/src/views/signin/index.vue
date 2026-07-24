<template>
  <div class="signin">
    <div class="flex justify-center items-center">
      <a-form :label-col="{ span: 4 }" :model="formState" :wrapper-col="{ span: 24 }" autocomplete="off"
        label-align="right" name="basic" @finish="onFinish" layout="vertical">
        <h1 class="text-center font-bold">登录</h1>
        <a-form-item :rules="[{ required: true, message: '请输入用户名' }]" label="用户名" name="username">
          <a-input v-model:value="formState.username">
            <template #prefix>
              <UserOutlined />
            </template>
          </a-input>
        </a-form-item>

        <a-form-item :rules="[{ required: true, message: '请输入密码' }]" label="密码" name="password">
          <a-input-password v-model:value="formState.password">
            <template #prefix>
              <LockOutlined />
            </template>
          </a-input-password>
        </a-form-item>
        <a-form-item :rules="[{ required: true, message: '请输入验证码' }]" label="验证码" name="code">
          <a-input v-model:value="formState.code">
            <template #prefix>
              <QrcodeOutlined />
            </template>
          </a-input>
        </a-form-item>
        <a-form-item v-if="state.imgSrc">
          <img :src="`data:image/png;base64,${state.imgSrc}`" @click="getVerifiCode" />
        </a-form-item>
        <a-form-item style="text-align: center">
          <a-button html-type="submit" type="primary"><login-outlined />登录</a-button>
        </a-form-item>
        还没有注册账号？<router-link to="signup"> 注册</router-link>
      </a-form>
    </div>
    <Copyright />
  </div>
</template>
<script lang="ts">
import { useUserStore } from '../../stores/user';
import {  useFetch, useCookie } from "../../hooks/";
import { message } from "ant-design-vue";
import { defineComponent, reactive, onBeforeMount } from "vue";
import  Copyright from '@/components/copyright/index.vue';
const cookie = useCookie;

interface FormState {
  username: string;
  password: string;
  code: string;
}

export default defineComponent({
  components: {Copyright},
  setup() {
    const userStore = useUserStore()
    const state = reactive({
      uuid: "",
      imgSrc: "",
    });
    const getVerifiCode = async () => {
      const { data } = await useFetch("/captchaImage").get().json();
      if (data?.value) {
        state.imgSrc = data?.value?.img;
        state.uuid = data?.value?.uuid;
      }
    };
    onBeforeMount(async () => {
      sessionStorage.clear();
      cookie.remove('token');
      getVerifiCode();
    });

    const formState = reactive<FormState>({
      username: "",
      password: "",
      code: "",
    });

    const onFinish = async (values: any) => {
      const { data } = await useFetch("/login")
        .post({ ...values, uuid: state.uuid })
        .json();
      if (data?.value) {
        cookie.remove("token");
        localStorage.setItem('token', data.value.token);
        cookie.set("token", data.value.token);
        await userStore.getUserInfo();
        message.success("登录成功");
        window.location.href = "/application";
      } else {
        getVerifiCode();
      }
    };

    return {
      state,
      onFinish,
      formState,
      getVerifiCode,
    };
  },
});
</script>
<style lang="scss" scoped>
.signin {
  height: 100%;
  background: rgba(255, 255, 255, 1);

  >div {
    width: 100%;
    height: 100%;
    background: rgba(67, 81, 232, 0.05);

    form {
      border-radius: 10px;
      border: 1px solid;
      border-color: rgba(229, 231, 235, 1);
      padding: 20px 30px;
      background: #fff;
      width: 500px;
      box-shadow: 0 20px 25px -5px rgb(0 0 0/0.1),
        0 8px 10px -6px rgb(0 0 0/0.1);
    }
  }

  img {
    width: 100%;
    height: 100px;
  }
}

:deep(.ant-input) {
  padding: 6.5px 11px !important;
}
</style>
