<template>
  <a-layout-content>
    <div class="layout-header">
      <div class="search-container">
        <div class="search-fields">
          <a-input
            v-model:value="title"
            allowClear
            placeholder="搜索标题"
            size="large"
            class="search-input"
          >
            <template #prefix>
              <SearchOutlined />
            </template>
          </a-input>
          <a-range-picker
            v-model:value="dateRange"
            :show-time="{ format: 'HH:mm:ss' }"
            format="YYYY-MM-DD HH:mm:ss"
            size="large"
            class="date-picker"
          >
            <template #suffixIcon>
              <CalendarOutlined />
            </template>
          </a-range-picker>
          <a-input
            v-model:value="description"
            allowClear
            placeholder="搜索简介"
            size="large"
            class="search-input"
          >
            <template #prefix>
              <FileTextOutlined />
            </template>
          </a-input>
        </div>
        <div class="action-buttons">
          <ActionButtons
            :is-admin="isAdmin"
            @system-settings="goToSystem"
            @clear="handleClear"
            @logout="handleLogout"
            @importData="handleImportData"
          />
        </div>
      </div>
    </div>
    <div class="layout-main">
      <AppMain />
    </div>
  </a-layout-content>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useSearchStore } from "../../stores/search"
import AppMain from "./main.vue"
import { useFetch } from "../../hooks"
import { storeToRefs } from "pinia"
import { checkRole } from "../../utils/permission"
import { useUserStore } from "../../stores/user"
import ActionButtons from './components/ActionButtons.vue'
import { SearchOutlined, CalendarOutlined, FileTextOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'

// Store initialization
const {title,dateRange,description} = storeToRefs(useSearchStore());
const userStore = useUserStore()


// Computed properties
const isAdmin = computed(() => checkRole(['admin']))

// API endpoints
const API_ENDPOINTS = {
  removeAll: "/system/note/removeAll",
  removeAllData: "/system/note/removeAllData",
  dataBak: "/system/note/dataBak",
} as const

// Event handlers
const handleClear = async (type: keyof typeof API_ENDPOINTS) => {
  try {
    await useFetch(API_ENDPOINTS[type]).get()
  } catch (error) {
    console.error(`Failed to execute ${type} operation:`, error)
  }
}

const handleLogout = () => {
  userStore.logout()
}

const goToSystem = () => {
  window.open(`${window.location.origin}/system/#/index`, "_blank")
}

const handleImportData = (content: string, fileName: string) => {
  try {
    console.log('导入的文件:', fileName)
    console.log('文件内容:', content)
    
    message.success(`成功导入文件: ${fileName}`)
    
    
  } catch (error) {
    console.error('处理导入数据时出错:', error)
    message.error('处理导入数据失败')
  }
}
</script>

<style scoped>
.layout-header {
  background: #fff;
  padding: 16px 24px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}

.search-container {
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.search-fields {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: 1;
}

.search-input {
  width: 240px;
}

.date-picker {
  width: 360px;
}

.action-buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.layout-main {
  padding: 24px;
  height: calc(100vh - 112px);
}

:deep(.ant-input-affix-wrapper) {
  border-radius: 6px;
}

:deep(.ant-picker) {
  border-radius: 6px;
}

:deep(.ant-input) {
  border-radius: 6px;
}

:deep(.ant-btn) {
  border-radius: 6px;
}
</style>

<script lang="ts">
export default {
  name: 'ContainerLayout'
}
</script>