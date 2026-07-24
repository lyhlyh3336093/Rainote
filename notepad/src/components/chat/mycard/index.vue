<script setup lang="ts">
import useChat from "@/hooks/useChat";

const {changeRoom, room} = useChat();

</script>

<template>
  <div class="mycard">
    <header class="flex justify-center">
      <a-avatar class="cursor-pointer">
        <template #icon>
          <UserOutlined/>
        </template>
      </a-avatar>
    </header>
    <div class="navbar flex flex-col items-center">
      <div class="cursor-pointer" @click="changeRoom('chat')">
        <span class="icon iconfont icon-msg " :class="{active:room==='chat'}"></span>
      </div>
      <div class="cursor-pointer" @click="changeRoom('friend')">
        <span class="icon iconfont icon-friend" :class="{active:room==='friend'}"></span>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.mycard {
  position: relative;
  width: 100%;
  height: 100%;

  .avatar {
    width: 36px;
    height: 36px;
    margin: 20px 12px 0 12px;
    border-radius: 2px;
  }

  .navbar {
    width: 100%;
  }

  .icon {
    display: inline-block;
    font-size: 26px;
    margin-top: 28px;
    box-sizing: border-box;
    color: rgb(173, 174, 175);
    opacity: 0.8;
    cursor: pointer;

    &.active {
      color: rgb(0, 220, 65);
    }

    &:hover {
      opacity: 1;
    }
  }

  .icon-msg, .icon-more {
    font-size: 22px;
  }

}
</style>
