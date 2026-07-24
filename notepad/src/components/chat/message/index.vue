<script setup>
import {ref} from "vue";
import useChat from "@/hooks/useChat.ts";

const {selectedChat} = useChat();

</script>

<template>
  <div class="message">
    <template v-if="selectedChat">
      <header class="header">
        <div class="friendname">{{ selectedChat.user.userName }}</div>
      </header>
      <div class="message-wrapper" ref="list">
        <ul>
          <li v-for="item in selectedChat.messages" class="message-item">
            <div class="time"><span>{{ item.date }}</span></div>
            <div class="main  " :class="{ self: item.self }">
              <a-avatar shape="square" :size="40">
                <template #icon>
                  <UserOutlined/>
                </template>
              </a-avatar>
              <div class="content">
                <div class="text" v-html="(item.content)"></div>
              </div>
            </div>
          </li>
        </ul>
      </div>
    </template>

  </div>
</template>

<style scoped lang="scss">
.message {
  width: 100%;
  height: 450px;

  .header {
    height: 60px;
    padding: 10px 0 0 30px;
    box-sizing: border-box;
    border-bottom: 1px solid #e7e7e7;
  }

  .friendname {
    font-size: 25px;
  }

  .message-wrapper {
    min-height: 390px;
    max-height: 390px;
    padding: 10px 15px;
    box-sizing: border-box;
    overflow-y: auto;
    border-bottom: 1px solid #e7e7e7;
  }

  .message {
    margin-bottom: 15px;
  }

  .time {
    width: 100%;
    font-size: 12px;
    margin: 7px auto;
    text-align: center;

    span {
      display: inline-block;
      padding: 4px 6px;
      color: #fff;
      border-radius: 3px;
      background-color: #dcdcdc;
    }

  }

  .main {
    margin-left: 15px;
    border-radius: 3px;
  }

  .content {
    display: inline-block;
    margin-left: 10px;
    position: relative;
    padding: 6px 10px;
    max-width: 330px;
    min-height: 36px;
    line-height: 24px;
    box-sizing: border-box;
    font-size: 14px;
    text-align: left;
    word-break: break-all;
    background-color: #fafafa;
    border-radius: 4px;

    &:before {
      content: " ";
      position: absolute;
      top: 12px;
      right: 100%;
      border: 6px solid transparent;
      border-right-color: #fafafa;
    }
  }

  .self {
    text-align: right;

    .content {
      background-color: #b2e281;

      &:before {
        right: -12px;
        vertical-align: middle;
        border-right-color: transparent;
        border-left-color: #b2e281;
      }
    }

    :deep(.ant-avatar) {
      float: right;
      margin: 0 15px;
    }
  }


}


</style>
