<script setup>
import {onMounted, ref} from "vue";
import useChat from "@/hooks/useChat.ts";

const {selectFriendId, selectFriend, searchedFriendlist, getFriendlist,} = useChat();

onMounted(() => {
  getFriendlist();
})
</script>

<template>
  <div class="friendlist">
    <ul>
      <li class="frienditem">
        <div class="list_title">添加朋友</div>
        <div class="friend-info" :class="{ active:  selectFriendId===-1 }" @click="selectFriend({friendId:-1})">
          <img class="avatar" src="@/assets/images/newfriend.jpg">
          <div class="remark">添加朋友</div>
        </div>
      </li>
      <li class="frienditem">
        <div class="list_title">新的朋友</div>
        <div class="friend-info" :class="{ active:  selectFriendId===-2 }" @click="selectFriend({friendId:-2})">
          <a-badge count="5">
            <img class="avatar" src="@/assets/images/newfriend.jpg">
          </a-badge>
          <div class="remark">新的朋友</div>
        </div>
      </li>
      <li class="frienditem">
        <div class="list_title">新的朋友</div>
        <div class="friend-info" :class="{ active:  selectFriendId===-3 }" @click="selectFriend({friendId:-3})">
          <a-badge count="5">
            <img class="avatar" src="@/assets/images/newfriend.jpg">
          </a-badge>
          <div class="remark">黑名单</div>
        </div>
      </li>
      <li v-for="item in searchedFriendlist" class="frienditem" :class="{ noborder: !item.initial}">
        <div class="friend-info" :class="{ active: item.friendId === selectFriendId }" @click="()=>selectFriend(item)">
          <a-avatar :size="36" class="avatar" shape="square">
            <template #icon>
              <UserOutlined/>
            </template>
          </a-avatar>

          <div class="remark">{{ item.userName }}</div>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped lang="scss">
.friendlist {
  height: 534px;
  overflow-y: auto;

  .list_title {
    box-sizing: border-box;
    width: 100%;
    font-size: 12px;
    padding: 15px 0 3px 12px;
    color: #999;
  }

  .friend-info {
    cursor: pointer;
    display: flex;
    padding: 12px;
    transition: background-color 0.1s;
    font-size: 0;

    &:hover {
      background-color: #dcdcdc;
    }

    &.active {
      background-color: #c4c4c4
    }

    img {
      width: 36px;
      height: 36px;
      border-radius: 2px;
    }

    .remark {
      font-size: 14px;
      line-height: 36px;
      margin-left: 12px;
    }
  }
}
</style>
