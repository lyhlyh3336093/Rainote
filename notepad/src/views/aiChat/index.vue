<template>
  <div class="ai-chat-container">
    <!-- 非 VIP 用户入口角标（U7）：点击跳 /vip 升级 -->
    <a-tooltip v-if="showVipBadge" title="升级 VIP 解锁更高对话配额">
      <span class="vip-entry-badge" @click="goVip">VIP</span>
    </a-tooltip>
    <ConversationSidebar />
    <ChatArea />
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAiChatStore } from '../../stores/aiChat';
import { getVipInfo } from '../../api/vip';
import ConversationSidebar from './ConversationSidebar.vue';
import ChatArea from './ChatArea.vue';

const store = useAiChatStore();
const router = useRouter();

// VIP 身份按需拉取（U7）：非 VIP 且非管理员时显示角标
const vipStatus = ref<{ vip: boolean; admin: boolean } | null>(null);
const showVipBadge = computed(() => !!vipStatus.value && !vipStatus.value.vip && !vipStatus.value.admin);

const goVip = () => {
  router.push('/vip');
};

onMounted(async () => {
  try {
    const info = await getVipInfo();
    if (info.code === 200) {
      vipStatus.value = info.status;
    }
  } catch {
    // 拉取失败不显示角标，不阻塞对话
  }
  await store.loadSessions();
  // R10/F4: 恢复上次选择的会话
  if (store.sessions.length > 0) {
    await store.selectSession(store.sessions[0].id);
  }
});
</script>

<style lang="scss" scoped>
.ai-chat-container {
  display: flex;
  width: 100%;
  height: 100%;
  position: relative;

  > :first-child {
    width: 250px;
    flex-shrink: 0;
  }

  > :last-child {
    flex: 1;
    min-width: 0;
  }
}

// VIP 入口角标：金色小标签，悬浮于对话区右上角
.vip-entry-badge {
  position: absolute;
  top: 8px;
  right: 12px;
  z-index: 10;
  padding: 1px 8px;
  border-radius: 6px;
  background: linear-gradient(135deg, #f7c948, #e8a33d);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 1px;
  cursor: pointer;
  box-shadow: 0 1px 4px rgba(232, 163, 61, 0.4);
}
</style>
