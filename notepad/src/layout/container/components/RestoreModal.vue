<template>
  <a-modal
    v-model:open="state.visible"
    title="数据还原"
    width="640px"
    :footer="null"
    destroyOnClose
    centered
  >
    <div class="restore-body">
      <!-- 第一步：选择备份包并校验（预览步 confirmed=false，不写库） -->
      <template v-if="state.step === 'pick'">
        <a-alert type="warning" show-icon style="margin-bottom: 12px">
          <template #message>
            还原将全量替换系统数据（数据库表结构+数据、用户文件目录），不可部分还原；
            执行前自动生成还原前快照，失败自动回滚至还原前状态
          </template>
        </a-alert>
        <a-button type="primary" :loading="state.previewing" @click="pickFile">
          选择备份包（.zip）并校验
        </a-button>
      </template>

      <!-- 第二步：预览摘要 + sys_user 告警 + 确认门禁（预览成功才可确认） -->
      <template v-else-if="state.step === 'preview'">
        <a-alert type="error" show-icon style="margin-bottom: 12px">
          <template #message>全量替换警示：确认后当前系统数据将被备份包内容整体替换</template>
          <template #description>
            <div>表数 {{ state.preview?.tableCount }} · 记录数 {{ state.preview?.recordCount }} · 用户文件数 {{ state.preview?.fileCount }} · 系统账号数 {{ state.preview?.userCount }}</div>
          </template>
        </a-alert>
        <!-- sys_user 告警醒目展示（R5 界面侧；后端仅输出布尔，无任何密码值） -->
        <a-alert
          v-if="state.preview?.credentialChanged"
          type="warning"
          show-icon
          style="margin-bottom: 12px"
        >
          <template #message>检测到备份包内系统账号或凭据与当前库不同</template>
          <template #description>还原后系统账号/密码将变为备份包内版本，请确认备份包来源可信</template>
        </a-alert>
        <div class="restore-hint">
          备份包校验通过（尚未写库）。确认还原后将：生成还原前快照 → 重建库表并导入数据 →
          替换用户文件目录；还原期间系统短暂不可写。
        </div>
        <div style="text-align: right; margin-top: 16px">
          <a-button style="margin-right: 8px" :disabled="state.executing" @click="resetToPick">
            重新选择
          </a-button>
          <a-button type="primary" danger :loading="state.executing" @click="confirmRestore">
            确认还原（全量替换）
          </a-button>
        </div>
      </template>

      <!-- 第三步：执行结果（成功摘要 + 需重登提示） -->
      <template v-else-if="state.step === 'done'">
        <a-alert type="success" show-icon style="margin-bottom: 12px">
          <template #message>还原完成</template>
          <template #description>
            <div>表数 {{ state.result?.tableCount }} · 记录数 {{ state.result?.recordCount }} · 用户文件数 {{ state.result?.fileCount }}</div>
          </template>
        </a-alert>
        <!-- 需重登提示（R8 界面侧：包内 sys_user 不含当前用户） -->
        <a-alert
          v-if="state.result?.reloginRequired"
          type="warning"
          show-icon
          style="margin-bottom: 12px"
        >
          <template #message>当前登录会话不在备份包账号列表中，请退出并重新登录</template>
        </a-alert>
        <div style="text-align: right; margin-top: 16px">
          <a-button type="primary" @click="close">关闭</a-button>
        </div>
      </template>

      <input
        ref="fileInput"
        type="file"
        accept=".zip"
        style="display: none"
        @change="onFileChosen"
      />
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { Modal } from 'ant-design-vue'
import { restoreUpload } from '../../../api/backup'
import type { RestoreResult } from '../../../api/backup'

/** 还原执行步超时（毫秒）：还原含还原前快照生成（等于一次全量备份），远超常规预检时长 */
const RESTORE_EXECUTE_TIMEOUT_MS = 1800000

const props = defineProps<{
  /** 是否可见（v-model:open） */
  open: boolean
}>()

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
}>()

const fileInput = ref<HTMLInputElement>()

const state = reactive({
  visible: false,
  step: 'pick' as 'pick' | 'preview' | 'done',
  previewing: false,
  executing: false,
  file: null as File | null,
  preview: null as RestoreResult | null,
  result: null as RestoreResult | null,
})

// 遵循 ImportPrecheckModal 的 { immediate: true } open watch 约定（#15：父组件同批设置
// open 时非 immediate 的 watch 挂载后无变化永不触发，弹框死锁不显示）
watch(() => props.open, (open) => {
  state.visible = open
}, { immediate: true })
watch(() => state.visible, (v) => {
  if (!v) reset()
  emit('update:open', v)
})

/** 重置到初始选择步（关闭弹窗或重新选择时） */
const reset = () => {
  state.step = 'pick'
  state.previewing = false
  state.executing = false
  state.file = null
  state.preview = null
  state.result = null
}

const resetToPick = () => {
  if (state.executing) return
  reset()
}

const close = () => {
  if (state.executing) return
  state.visible = false
}

const pickFile = () => {
  if (state.previewing) return
  fileInput.value?.click()
}

/** 选择备份包 → 校验预览步（confirmed=false，不写库，后端返回摘要与 sys_user 告警布尔） */
const onFileChosen = async (event: Event) => {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  // 清空文件选择，允许重复选择同一文件
  target.value = ''
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.zip')) {
    Modal.error({ title: '文件类型错误', content: '请选择 .zip 格式的整机备份包' })
    return
  }
  state.previewing = true
  try {
    const preview = await restoreUpload(file, false)
    state.file = file
    state.preview = preview
    state.step = 'preview'
  } catch (e: any) {
    Modal.error({ title: '备份包校验失败', content: e?.message || '校验失败，请检查备份包' })
  } finally {
    state.previewing = false
  }
}

/** 确认还原 → 执行步（confirmed=true，后端重跑全部校验，不跨请求信任预览结果） */
const confirmRestore = async () => {
  if (state.executing || !state.file || !state.preview) return
  state.executing = true
  try {
    const result = await restoreUpload(state.file, true, { timeoutMs: RESTORE_EXECUTE_TIMEOUT_MS })
    state.result = result
    state.step = 'done'
  } catch (e: any) {
    // 失败文案含后端回滚说明（"已回滚至还原前状态"）；执行中请勿重复上传，见 backup.ts 超时文案
    Modal.error({ title: '还原失败', content: e?.message || '还原失败，请稍后重试' })
  } finally {
    state.executing = false
  }
}
</script>

<script lang="ts">
export default {
  name: 'RestoreModal'
}
</script>

<style scoped>
.restore-body {
  max-height: 55vh;
  overflow: auto;
  padding: 0 4px;
}

.restore-hint {
  color: #555;
  font-size: 13px;
  line-height: 1.6;
}
</style>
