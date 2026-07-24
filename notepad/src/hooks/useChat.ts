import {onMounted, ref} from "vue";
import useFetch from "./useFetch.ts";
import {useUserStore} from "../stores/user";
import {storeToRefs} from "pinia";
import {message} from "ant-design-vue";
import dayjs from "dayjs";
import {FriendEnum} from "../enum";

// 消息列表
const searchedChatlist = ref([]);

// 朋友列表
const searchedFriendlist = ref([]);

// 所有用户
const allUser = ref([]);

const selectedChat = ref();
const selectedChatId = ref();


const newFriendList = ref([]);

const selectFriendId = ref();

const selectedFriend = ref();

const searchText = ref('');

const content = ref('');

const room = ref('chat');
const blockList = ref([]);
const userStore = useUserStore();
const {userInfo} = storeToRefs(userStore);
const useChat = () => {

    const getFriendlist = async () => {
        const {data}: any = await useFetch(`system/friend/list?userId=${userInfo.value.user.userId}`).get().json();
        if (data?.value?.code === 200) {
            searchedFriendlist.value = data.value.data;
        }
    }

    const addFriend = async (item) => {
        await useFetch(`/system/request/add`).post({
            "requestId": item.userId,
            "userId": userInfo.value.user.userId,
            "requestName": item.userName,
            "description": item.description
        }).json();
        message.success('发送成功');
        item.visible = false;
        getAllUser();
    }


    const getNewFriend = async () => {
        const {data}: any = await useFetch(`/system/request/list`).get().json();
        newFriendList.value = data.value.data;
    }
    const restore = async (item) => {
        const {data} = await useFetch(`/system/friend/edit`).post({
            "userId": item.userId,
            "friendId": item.friendId,
            "status": FriendEnum.好友
        }).json();
        if (data?.value?.code === 200) {
            message.success('操作成功');
            getBlockList();
            getFriendlist();
        }
    }
    const deleteOrBlack = async (action, item) => {
        let data = null;
        if (action === 0) {
            data = await useFetch(`/system/friend/blockFriend`).post({
                "userId": item.userId,
                "friendId": item.friendId,
                "status": FriendEnum.拉黑
            }).json();
        } else {
            data = await useFetch(`/system/friend/remove`).post({
                "userId": item.userId,
                "friendId": item.friendId,
            }).json();
        }
        if (data?.value?.code === 200) {
            message.success('操作成功');
            getFriendlist();
        }
    }
    const agree = async item => {
        await useFetch(`/system/friend/add`).post({
            "friendId": item.userId,
            "userId": userInfo.value.user.userId,
        }).json();
        message.success('操作成功');
        getNewFriend();
        getFriendlist();
    }
    const refuse = async item => {
        await useFetch(`/system/request/refuse`).post(item).json();
        message.success('操作成功');
        getNewFriend();
        getFriendlist();
    }

    const getAllUser = async () => {
        const {data}: any = await useFetch(`/system/user/allList`).get().json();
        allUser.value = data.value.data.map(item => {
            return {
                ...item,
                visible: false,
                description: `我是${userInfo.value.user.userName}`,
            }
        });
    }

    const changeRoom = value => {
        selectFriendId.value = null;
        room.value = value;
    }
    const send = async () => {
        if (content.value.length <= 0) {
            message.warning('不能发送空白信息');
            return;
        }
        const {data} = await useFetch('system/friend/sendMessageToSomeone').post({
            userId: userInfo.value.user.userId,
            friendId: selectedFriend.value.friendId,
            status: selectedFriend.value.status,
            message: content.value,
            creatTime: selectedFriend.value.createTime,
        }).json();
        if (data?.value?.code === 200) {
            selectedChat.value.messages.push({
                self: true,
                content: content.value,
                date: `${dayjs().hour()}:${dayjs().minute()}`
            })
        }
        content.value = '';
    }


    const selectSession = (item) => {

    }

    const selectFriend = friend => {
        const {friendId} = friend;
        selectFriendId.value = friendId;
        selectedFriend.value = friend;
    }

    const goChat = () => {
        room.value = 'chat';
        if (!searchedChatlist.value.some(item => item.user.userId === selectedFriend.value.userId)) {
            searchedChatlist.value.unshift({
                user: selectedFriend.value,
                messages: [
                    {
                        self: false,
                        content: '已经置顶聊天，可以给我发信息啦！',
                        date: `${dayjs().hour()}:${dayjs().minute()}`
                    }
                ]
            });
        }
        selectedChat.value = searchedChatlist.value.find(item => item.user.userId === selectedFriend.value.userId);
        selectedChatId.value = selectedFriend.value.userId;
    }


    const getBlockList = async () => {
        const {data} = await useFetch(`system/friend/selectFriendBlockList?userId=${userInfo.value.user.userId}`).get().json();
        blockList.value = data.value.data;
    }


    return {
        room,
        send,
        agree,
        refuse,
        goChat,
        content,
        allUser,
        restore,
        userInfo,
        addFriend,
        blockList,
        changeRoom,
        searchText,
        getAllUser,
        selectedChat,
        selectFriend,
        getNewFriend,
        getBlockList,
        getFriendlist,
        newFriendList,
        deleteOrBlack,
        selectedChatId,
        selectFriendId,
        selectedFriend,
        searchedChatlist,
        searchedFriendlist,
    }

}
export default useChat;
