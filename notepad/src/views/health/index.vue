<template>
    <a-layout>
        <a-layout-header class="header flex items-center">
            <a-page-header title="健康管理系统" @back="() => router.push('/')" />
        </a-layout-header>
        <a-layout>
            <a-layout-sider>
                <a-menu v-model:selectedKeys="selectedKeys" mode="inline" @click="handleClick">
                    <a-menu-item v-for="item in health" :key="item.name">
                        {{ item.meta.title }}
                    </a-menu-item>
                </a-menu>
            </a-layout-sider>
            <a-layout-content>
                <router-view />
            </a-layout-content>
        </a-layout>
    </a-layout>
</template>
<script lang="tsx" setup>
import { health } from '../../router';
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router'
const router = useRouter();
const selectedKeys = ref([]);
const handleClick = (menuInfo) => {
    router.push({
        name: menuInfo.key
    })
}
const route = useRoute();

watch(() => route, val => {
    selectedKeys.value = [val.name];
}, { immediate: true })

</script>

<style scoped lang="scss">
.header {
    background: #ffffff;

    .ant-page-header {
        padding: 0;
    }
}

.ant-layout-has-sider {
    padding-top: 20px;
}

.ant-layout-sider {
    height: 100%;
    padding: 0;
    margin: 0;
    margin-right: 20px;
}

.ant-layout-content {
    padding: 0;

    .health-module {
        padding: 0;
    }
}
</style>
