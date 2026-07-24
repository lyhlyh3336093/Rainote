<template>
  <div class="message-bubble" :class="msg.role">
    <div class="avatar">
      <a-avatar :size="32" :style="{ background: msg.role === 'user' ? '#1890ff' : '#00b96b' }">
        {{ msg.role === 'user' ? '我' : 'AI' }}
      </a-avatar>
    </div>
    <div class="content-wrapper">
      <div v-if="msg.role === 'user'" class="content user-content">{{ msg.content }}</div>
      <div v-else class="content assistant-content" v-html="renderedContent"></div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue';
import { renderMarkdown } from '../../utils/markdown';
import type { ChatMessage } from '../../stores/aiChat';

const props = defineProps<{ msg: ChatMessage }>();

const renderedContent = computed(() => {
  if (!props.msg.content) {
    return '<span class="cursor-blink">▋</span>';
  }
  // R6a/Group B — 经 DOMPurify 净化的 HTML
  return renderMarkdown(props.msg.content);
});
</script>

<style lang="scss" scoped>
.message-bubble {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;

  .avatar {
    flex-shrink: 0;
  }

  .content-wrapper {
    flex: 1;
    min-width: 0;
  }

  .content {
    padding: 10px 14px;
    border-radius: 8px;
    line-height: 1.6;
    word-break: break-word;
    font-size: 14px;
  }

  .user-content {
    background: #e6f7ff;
    color: #333;
  }

  .assistant-content {
    background: #f6f6f6;
    color: #333;

    :deep(h1), :deep(h2), :deep(h3) {
      margin: 12px 0 8px;
      font-weight: 600;
    }

    :deep(p) {
      margin: 8px 0;
    }

    :deep(pre) {
      background: #282c34;
      border-radius: 6px;
      padding: 12px;
      overflow-x: auto;
      margin: 8px 0;

      code {
        color: #abb2bf;
        background: none;
        padding: 0;
        font-size: 13px;
      }
    }

    :deep(code) {
      background: rgba(0, 0, 0, 0.06);
      padding: 2px 4px;
      border-radius: 3px;
      font-size: 13px;
    }

    :deep(table) {
      border-collapse: collapse;
      width: 100%;
      margin: 8px 0;

      th, td {
        border: 1px solid #ddd;
        padding: 6px 12px;
        text-align: left;
      }

      th {
        background: #f0f0f0;
        font-weight: 600;
      }
    }

    :deep(ul), :deep(ol) {
      padding-left: 20px;
      margin: 8px 0;
    }

    :deep(blockquote) {
      border-left: 4px solid #ddd;
      padding-left: 12px;
      margin: 8px 0;
      color: #666;
    }
  }
}

.cursor-blink {
  animation: blink 1s infinite;
  color: #999;
}

@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}
</style>
