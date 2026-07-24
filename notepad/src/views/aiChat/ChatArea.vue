<template>
  <div class="chat-area">
    <div class="message-list" ref="messageListRef">
      <a-spin :spinning="store.isLoadingMessages">
        <div v-if="store.messages.length === 0 && !store.isLoadingMessages" class="empty-state">
          <p>开始一段新对话吧</p>
        </div>
        <template v-for="(msg, idx) in store.messages" :key="idx">
          <MessageBubble :msg="msg" />
        </template>
      </a-spin>
    </div>
    <div class="input-area">
      <a-textarea
        v-model:value="inputText"
        placeholder="输入消息，Enter发送，Shift+Enter换行"
        :auto-size="{ minRows: 1, maxRows: 4 }"
        @keydown.enter.exact.prevent="handleSend"
        :disabled="store.isStreaming"
      />
      <!-- R5a/Group D1 — 流式时发送按钮变形为停止生成 -->
      <a-button
        v-if="store.isStreaming"
        danger
        @click="handleStop"
      >
        停止生成
      </a-button>
      <a-button
        v-else
        type="primary"
        @click="handleSend"
        :disabled="!inputText.trim() || store.isStreaming || !store.activeSessionId"
      >
        发送
      </a-button>
    </div>
    <!-- Group S — PII 披露文案 -->
    <div class="pii-disclosure">
      请勿在对话中提交敏感个人信息（如身份证号、银行卡号、密码等）。
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref, watch, nextTick } from 'vue';
import { useAiChatStore } from '../../stores/aiChat';
import MessageBubble from './MessageBubble.vue';

const store = useAiChatStore();
const inputText = ref('');
const messageListRef = ref<HTMLElement>();

const handleSend = () => {
  const content = inputText.value.trim();
  if (!content || store.isStreaming || !store.activeSessionId) return;
  inputText.value = '';
  store.sendMessage(content);
};

// R5a/Group D1 — 停止生成
const handleStop = () => {
  store.abortStream();
};

// 消息变化时滚动到底部
watch(
  () => store.messages.length,
  () => {
    nextTick(() => {
      if (messageListRef.value) {
        messageListRef.value.scrollTop = messageListRef.value.scrollHeight;
      }
    });
  }
);

// 流式内容更新时也滚动
watch(
  () => store.messages[store.messages.length - 1]?.content,
  () => {
    nextTick(() => {
      if (messageListRef.value) {
        messageListRef.value.scrollTop = messageListRef.value.scrollHeight;
      }
    });
  }
);
</script>

<style lang="scss" scoped>
.chat-area {
  display: flex;
  flex-direction: column;
  height: 100%;

  .message-list {
    flex: 1;
    overflow-y: auto;
    padding: 16px;

    &::-webkit-scrollbar {
      width: 6px;
    }

    &::-webkit-scrollbar-thumb {
      background: #ccc;
      border-radius: 3px;
    }

    &::-webkit-scrollbar-track {
      background: #f1f1f1;
    }
  }

  .empty-state {
    text-align: center;
    color: #999;
    padding: 60px 0;
    font-size: 14px;
  }

  .input-area {
    display: flex;
    gap: 8px;
    padding: 12px 16px;
    border-top: 1px solid #f0f0f0;
    align-items: flex-end;

    :deep(.ant-input) {
      flex: 1;
      resize: none;
    }
  }

  .pii-disclosure {
    padding: 4px 16px 8px;
    font-size: 11px;
    color: #999;
    text-align: center;
  }
}
</style>
