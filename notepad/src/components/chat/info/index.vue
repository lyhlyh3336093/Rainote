<script setup>
import useChat from "@/hooks/useChat.ts";
import {watch} from "vue";

const {
  selectedFriend,
  goChat,
  userInfo,
  getNewFriend,
  newFriendList,
  getAllUser,
  allUser,
  addFriend,
  selectFriendId,
  refuse,
  agree,
  deleteOrBlack,
  getBlockList,
  blockList,
  restore,
} = useChat();

watch(() => selectFriendId.value, val => {
  if (val === -1) {
    getAllUser();
  }
  if (val === -2) {
    getNewFriend();
  }
  if (val === -3) {
    getBlockList();
  }
}, {
  deep: true,
  immediate: true
})

</script>

<template>
  <div class="info-wrapper h-full overflow-auto p-3">
    <div class="request-list" v-if="selectFriendId === -1">
      <a-list
          item-layout="horizontal"
          :data-source="allUser"
      >
        <template #renderItem="{ item }">
          <a-list-item>
            <template #actions>
              <a-popover v-model:visible="item.visible" placement="right" title="发送添加好友申请" trigger="click">
                <template #content>
                  <div class="flex flex-col">
                    <a-textarea ref="text" v-model:value="item.description" :bordered="false" :rows="3"/>
                    <a-button @click="addFriend(item)" type="primary">发送</a-button>
                  </div>
                </template>
                <a-button type="primary">添加好友</a-button>
              </a-popover>
            </template>
            <a-skeleton avatar :title="false" :loading="!!item.loading" active>
              <a-list-item-meta
              >
                <template #title>
                  {{ item.userName }}
                </template>
                <template #avatar>
                  <a-avatar shape="square">
                    <template #icon>
                      <UserOutlined/>
                    </template>
                  </a-avatar>
                </template>
              </a-list-item-meta>
            </a-skeleton>
          </a-list-item>
        </template>
      </a-list>
    </div>
    <div class="newfriend-list" v-if="selectFriendId === -2">
      <a-list
          item-layout="horizontal"
          :data-source="newFriendList"
      >
        <template #renderItem="{ item }">
          <a-list-item>
            <template #actions>
              <a-button @click="agree(item)" type="primary">通过</a-button>
              <a-button @click="refuse(item)" type="primary" danger>拒绝</a-button>
            </template>
            <a-skeleton avatar :title="false" :loading="!!item.loading" active>
              <a-list-item-meta
                  :description="item.description"
              >
                <template #title>
                  {{ item.userName }}
                </template>
                <template #avatar>
                  <a-avatar shape="square">
                    <template #icon>
                      <UserOutlined/>
                    </template>
                  </a-avatar>
                </template>
              </a-list-item-meta>
            </a-skeleton>
          </a-list-item>
        </template>
      </a-list>
    </div>
    <div class="block-List" v-if="selectFriendId === -3">
      <a-list
          item-layout="horizontal"
          :data-source="blockList"
      >
        <template #renderItem="{ item }">
          <a-list-item>
            <template #actions>
              <a-button @click="restore(item)" type="primary">移除黑名单</a-button>
            </template>
            <a-skeleton avatar :title="false" :loading="!!item.loading" active>
              <a-list-item-meta
              >
                <template #title>
                  {{ item.userName }}
                </template>
                <template #avatar>
                  <a-avatar shape="square">
                    <template #icon>
                      <UserOutlined/>
                    </template>
                  </a-avatar>
                </template>
              </a-list-item-meta>
            </a-skeleton>
          </a-list-item>
        </template>
      </a-list>
    </div>
    <div class="friendInfo" v-if="selectFriendId>0">
      <div class="esInfo">
        <div class="left">
          <div class="people">
            <div class="nickname">{{ selectedFriend.userName }}</div>
            <div :class="[selectedFriend.sex===1?'gender-male':'gender-female']"></div>
          </div>
          <div class="signature">{{ selectedFriend.remark }}</div>
        </div>
        <div class="right">
          <a-avatar :size="60" shape="square">
            <template #icon>
              <UserOutlined/>
            </template>
          </a-avatar>
        </div>
      </div>
      <div class="detInfo">
        <div class="remark"><span>备&nbsp&nbsp&nbsp注</span>{{ selectedFriend.remark }}</div>
        <div class="area"><span>地&nbsp&nbsp&nbsp区</span>{{ selectedFriend.area }}</div>

      </div>
      <div class="send flex justify-center w-full">
        <a-button type="primary" size="large" @click="goChat">发消息</a-button>
        <a-button @click="deleteOrBlack(1,selectedFriend)" class="mx-2" type="primary" size="large" danger>删除好友
        </a-button>
        <a-button @click="deleteOrBlack(0,selectedFriend)" size="large">拉黑好友</a-button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.friendInfo {
  padding: 0 90px;

  .esInfo {
    display: flex;
    align-items: center;
    padding: 100px 0 45px 0;

    .left {
      flex: 1;

      .signature {
        font-size: 14px;
        color: rgba(153, 153, 153, 0.8);
      }

      .nickname {
        display: inline-block;
        font-size: 20px;
        margin-bottom: 16px;
      }

      .gender-female, gender-male {
        display: inline-block;
        width: 18px;
        height: 18px;
        vertical-align: top;
        margin-top: 2px;
      }

      .gender-female {
        background: url('@/assets/images/woman.png') no-repeat center center /100% 100%;
      }

      .gender-male {
        background: url('@/assets/images/man.png') no-repeat center center /100% 100%;
      }
    }
  }

  .detInfo {
    padding: 40px 0;
    border-top: 1px solid #e7e7e7;
    border-bottom: 1px solid #e7e7e7;

    > div {
      font-size: 14px;
      margin-top: 20px;

      &:first-child {
        margin: 0;
      }
    }
  }

  .send {
    position: relative;
    text-align: center;
    top: 50px;
    line-height: 36px;
    font-size: 14px;
    border-radius: 2px;
  }
}
</style>
