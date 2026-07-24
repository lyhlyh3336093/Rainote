<template>
  <div class="ai-chat-container">
    <ConversationSidebar />
    <ChatArea />
  </div>
</template>

<script lang="ts" setup>
import { onMounted } from 'vue';
import { useAiChatStore } from '../../stores/aiChat';
import ConversationSidebar from './ConversationSidebar.vue';
import ChatArea from './ChatArea.vue';

const store = useAiChatStore();

onMounted(async () => {
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

  > :first-child {
    width: 250px;
    flex-shrink: 0;
  }

  > :last-child {
    flex: 1;
    min-width: 0;
  }
}
</style>
