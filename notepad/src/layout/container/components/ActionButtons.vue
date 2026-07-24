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
    <a-button
      class="ml-2"
      @click="$emit('clear', 'dataBak')"
    >
      数据备份
    </a-button>
    <a-button
      class="ml-2"
      @click="$emit('logout')"
    >
      退出登录
    </a-button>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { useFetch } from '../../../hooks'
defineProps<{
  isAdmin: boolean
}>()

const emit = defineEmits<{
  (e: 'system-settings'): void
  (e: 'clear', type: 'removeAll' | 'removeAllData' | 'dataBak'): void
  (e: 'logout'): void
  (e: 'importData', content: string, fileName: string): void
}>()

const fileInput = ref<HTMLInputElement>()

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