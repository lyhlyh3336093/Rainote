<script setup>
import useChat from "@/hooks/useChat.ts";

const {searchedChatlist,selectedChatId,selectSession}=useChat();

</script>

<template>
  <div class="msglist">
    <ul>
      <li v-for="item in searchedChatlist" class="sessionlist cursor-pointer" :class="{ active: item.user.userId === selectedChatId }"
          @click="selectSession(item.id)">
        <div class="list-left mr-2">
          <a-avatar shape="square" :size="40">
            <template #icon>
              <UserOutlined/>
            </template>
          </a-avatar>
        </div>
        <div class="list-right">
          <p class="name">{{ item.user.userName }}</p>
          <span class="time">{{ item.messages[item.messages.length - 1].date  }}</span>
          <p class="lastmsg">{{ item.messages[item.messages.length - 1].content }}</p>
        </div>
      </li>
    </ul>

  </div>
</template>

<style scoped lang="scss">
.msglist {
  width: 250px;
  height: 540px;
  overflow-y: auto
}

.sessionlist {
  display: flex;
  padding: 12px;
  transition: background-color .1s;
  font-size: 0;

  &:hover {
    background-color: rgb(220, 220, 220);
  }

  &.active {
    background-color: #c4c4c4;
  }
}

.avatar {
  border-radius: 2px;
  margin-right: 12px;
}

.list-right {
  position: relative;
  flex: 1;
  margin-top: 4px;
}

.name {
  display: inline-block;
  vertical-align: top;
  font-size: 14px;
}

.time {
  float: right;
  color: #999;
  font-size: 10px;
  vertical-align: top;
}

.lastmsg {
  position: absolute;
  font-size: 12px;
  width: 130px;
  height: 15px;
  line-height: 15px;
  color: #999;
  bottom: 0px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  margin: 0;
}

</style>
