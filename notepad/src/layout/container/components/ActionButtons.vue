<template>
  <div class="action-buttons">
    <input
      ref="fileInput"
      type="file"
      accept=".txt,.doc,.docx"
      style="display: none"
      @change="handleFileSelect"
    />
    <a-button
      class="ml-2"
      @click="triggerFileSelect"
    >
      导入数据
    </a-button>
    <a-button
      v-if="isAdmin"
      class="ml-2"
      @click="$emit('system-settings')"
    >
      系统设置
    </a-button>
    <!-- <a-button
      class="ml-2"
      @click="$emit('clear', 'removeAll')"
    >
      系统重置
    </a-button>
    <a-button
      class="ml-2"
      @click="$emit('clear', 'removeAllData')"
    >
      清空数据
    </a-button> -->
    <!-- 整机备份下载（U3）：管理员专用，成功后浏览器保存备份包 zip -->
    <a-button
      v-if="isAdmin"
      class="ml-2"
      :loading="backupState.loading"
      @click="handleBackup"
    >
      数据备份
    </a-button>
    <!-- VIP 个人数据导出（U7）：仅打包本人数据；非 VIP 点击后被 602 拦截走升级引导 -->
    <a-button
      class="ml-2"
      :loading="exportState.loading"
      @click="handleMyExport"
    >
      个人导出
    </a-button>
    <!-- 数据还原入口（U3）：点击开两步还原弹窗（上传→预览摘要→确认执行） -->
    <a-button
      v-if="isAdmin"
      class="ml-2"
      @click="restoreOpen = true"
    >
      数据还原
    </a-button>
    <a-button
      class="ml-2"
      @click="$emit('logout')"
    >
      退出登录
    </a-button>
    <!-- 数据还原弹窗（上传备份包→预览摘要+sys_user 告警→确认还原→结果提示） -->
    <RestoreModal v-model:open="restoreOpen" />
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useFetch } from '../../../hooks'
import { downloadBackup, exportMyData } from '../../../api/backup'
import { handleVipCode } from '../../../api/vip'
import RestoreModal from './RestoreModal.vue'
defineProps<{
  isAdmin: boolean
}>()

const emit = defineEmits<{
  (e: 'system-settings'): void
  (e: 'clear', type: 'removeAll' | 'removeAllData'): void
  (e: 'logout'): void
  (e: 'importData', content: string, fileName: string): void
}>()

const fileInput = ref<HTMLInputElement>()

// 数据备份下载状态（loading 期间防重复触发）
const backupState = reactive({ loading: false })

// 数据还原弹窗开关（RestoreModal 以 v-model:open 接管）
const restoreOpen = ref(false)

// 整机备份下载（U3）：调 dataBak 端点，blob 经浏览器保存为备份包 zip
const handleBackup = async () => {
  if (backupState.loading) return
  backupState.loading = true
  try {
    await downloadBackup()
    message.success('备份包已生成并开始下载')
  } catch (error: any) {
    message.error(error?.message || '备份失败，请稍后重试')
  } finally {
    backupState.loading = false
  }
}

// 个人数据导出状态（loading 期间防重复触发）
const exportState = reactive({ loading: false })

// VIP 个人数据导出（U7）：非 VIP 被 602 拦截时走升级引导（handleVipCode 已弹窗，跳过默认报错）
const handleMyExport = async () => {
  if (exportState.loading) return
  exportState.loading = true
  try {
    await exportMyData()
    message.success('个人数据导出已生成并开始下载')
  } catch (error: any) {
    if (!handleVipCode(error?.code, error?.message)) {
      message.error(error?.message || '导出失败，请稍后重试')
    }
  } finally {
    exportState.loading = false
  }
}

const triggerFileSelect = () => {
  fileInput.value?.click()
}

const handleFileSelect = async (event: Event) => {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]

  if (!file) return

  try {
    const content = await readFileContent(file)
    if(content){
      emit('importData', content, file.name)
      message.success('文件导入成功')
    }else{
      message.error('文件导入失败，请检查文件格式')
    }
  } catch (error) {
    console.error('文件读取失败:', error)
    message.error('文件读取失败，请检查文件格式')
  }

  // 清空文件选择，允许重复选择同一文件
  target.value = ''
}

const readFileContent = async (file: File): Promise<string> => {
  const fileName = file.name.toLowerCase()

   if (fileName.endsWith('.doc') || fileName.endsWith('.docx')||fileName.endsWith('.txt')) {
    // 上传文件
    const formData = new FormData()
    formData.append('file', file)
    const { data, error } = await useFetch<string>('/system/note/import', {
      method: 'POST',
      body: formData
    }).json()

    if(error.value){
      message.error(error.value.message)
      return ''
    }else{
      return data.value as string
    }
  }
}

</script>

<style scoped>
.action-buttons {
  display: flex;
  align-items: center;
}
</style>

<script lang="ts">
export default {
  name: 'ActionButtons'
}
</script>
