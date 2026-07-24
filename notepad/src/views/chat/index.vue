<script setup lang="ts">
import {useUserStore} from "../../stores/user";

import Mycard from '@/components/chat/mycard/index.vue';
import Chatlist from '@/components/chat/chatlist/index.vue';
import Search from '@/components/chat/search/index.vue';
import Message from '@/components/chat/message/index.vue';
import Text from '@/components/chat/text/index.vue';
import Friendlist from '@/components/chat/friendlist/index.vue';
import Info from '@/components/chat/info/index.vue';

const userStore = useUserStore();
const {userInfo} = storeToRefs(userStore)
import WebsocketHeartbeatJs from 'websocket-heartbeat-js';
import {onMounted} from "vue";
import {storeToRefs} from "pinia";
import useChat from "@/hooks/useChat";
import dayjs from "dayjs";

const {room, getFriendlist, searchedChatlist} = useChat();

let websocketHeartbeatJs = new WebsocketHeartbeatJs({
  url: `ws://mvjk3nmt.ipyingshe.net/WebSocketServer/${userInfo.value.user.userId}`
});
websocketHeartbeatJs.onopen = () => {
  websocketHeartbeatJs.onmessage = message => {
    if (message.data === '连接成功') return;
    const data = JSON.parse(message.data);
    const chat = searchedChatlist.value.find(item => `${item.user.friendId}` === data.from);
    chat.messages.push({
      content: data.message,
      date: `${dayjs().hour()}:${dayjs().minute()}`,
      self: false,
    })
  }
}


onMounted(() => {
  getFriendlist();
})
</script>

<template>
  <div class="chat flex w-full">
    <div class="sidebar">
      <Mycard/>
    </div>
    <div class="user-list">

      <Search/>
      <Chatlist v-if="room==='chat'"/>
      <Friendlist v-else/>
    </div>
    <div class="chat-room">

      <div v-if="room==='chat'">
        <Message/>
        <Text/>
      </div>
      <div class="h-full" v-else>
        <Info/>
      </div>
    </div>
  </div>

</template>

<style scoped lang="scss">
.chat {
  height: 600px;
  background: #f2f2f2
}

.sidebar {
  width: 80px;
  padding: 24px;
  background: #2b2c2f;
}

.user-list {
  background: #e6e6e6;
}

.chat-room {
  flex: 1;


}

.input {
  border: 1px solid #d9d9d9;
}
</style>
