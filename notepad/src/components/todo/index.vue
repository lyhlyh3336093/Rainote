<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue';
import {useFetch, usePagination} from "@/hooks";
import {useUserStore} from "@/stores/user";
import {PlusOutlined} from '@ant-design/icons-vue'
import {Modal} from 'ant-design-vue'
import dayjs from 'dayjs';
import {useDraggable} from '@vueuse/core'

const todoRef = ref();
const teams = ref([]);
const defineState = {
  name: null,
  description: null,
  startTime: null,
  childrenTasks: [],
  endTime: null,
  status: null, //完成情况，0未完成，1已完成,
  teams: [],
  remindType: null,
  repeatType: null,
  importentLevel: null,
  urgentLevel: null,
  rewards: null,
  punishment: null,
  relevantAttribute: null,
  childrenTaskName: ''
}
const formState = reactive({...defineState});
const modalState = reactive({
  visible: false,
  title: '新增事项',
  type: 'add',
  row: {},
})
const getTeams = async () => {
  const {data} = await useFetch('/system/task/teamMembers').get().json();
  teams.value = data.value.data;
}

const {userInfo} = useUserStore();
const {  style } =useDraggable(todoRef,{
  initialValue: { x: window.innerWidth-400, y: 80 },
  preventDefault: true,
  exact: true,
});

onMounted(() => {
  getData();
  getTeams();
})
const {state, getData} = usePagination({
  url: `/system/task/list`,
  param: {
    leader: userInfo.user.userId
  }
});
const onFinish = async (values) => {
  let url = 'system/task/add';
  if (modalState.type === 'add') {
    url = 'system/task/add';
  } else {
    values.id = modalState.row.id;
    url = 'system/task/edit';
  }
  if (formState.childrenTasks.length > 0) {
    values.parentTask = modalState.row.id;
  }
  if (values.startTime) values.startTime = new Date(values.startTime).getTime();
  if (values.endTime) values.endTime = new Date(values.endTime).getTime();

  values.childrenTasks = values.childrenTasks.map(item => item.label).toString();
  values.teams = values.teams.toString();
  values.leader = userInfo.user.userId;
  const {data} = await useFetch(url).post(values).json();
  if (data?.value?.code === 200) {
    cancel();
    getData();
  }
}
const cancel = () => {
  modalState.visible = false;
  modalState.type = 'add';
  modalState.title = '新增事项';
  for (const itemKey in formState) {
    delete formState[itemKey]
  }
  for (const itemKey in defineState) {
    formState[itemKey] = defineState[itemKey]
  }
}
const edit = (item) => {
  modalState.type = 'edit';
  modalState.title = '编辑事项';
  modalState.visible = true;
  for (const itemKey in formState) {
    if (item[itemKey]) {
      if (itemKey === 'childrenTasks') {
        formState[itemKey] = item[itemKey].split(',');
      } else {
        formState[itemKey] = item[itemKey];
      }
      if (['startTime', 'endTime'].includes(itemKey)) {
        formState[itemKey] = dayjs(item[itemKey], 'YYYY-MM-DD')
      }
    }
  }
  console.log(formState)
  modalState.row = item;
}
const remove = (id) => {
  Modal.confirm({
    centered: true,
    title: '删除',
    content: '你确定要删除该待办事项吗？',
    okText: '删除',
    cancelText: '取消',
    async onOk() {
      await useFetch(`system/task/remove/${id}`);
      getData();
    }
  })
}
const addChildrenTasks = () => {
  formState.childrenTasks.push({
    value: formState.childrenTasks.length + 1,
    label: formState.childrenTaskName
  })
}
</script>

<template>
  <div class="to-do-list" ref="todoRef" :style="style">
    <div class="header flex justify-between">
      <div></div>
      <div>
        <plus-outlined @click="modalState.visible=true" class="cursor-pointer"/>
        <!--       -->
        <!--        <a-dropdown>-->
        <!--          <more-outlined class="cursor-pointer"/>-->
        <!--          <template #overlay>-->
        <!--            <a-menu>-->
        <!--              <a-menu-item>-->
        <!--                <a href="javascript:;">1st menu item</a>-->
        <!--              </a-menu-item>-->
        <!--              <a-menu-item>-->
        <!--                <a href="javascript:;">2nd menu item</a>-->
        <!--              </a-menu-item>-->
        <!--              <a-menu-item>-->
        <!--                <a href="javascript:;">3rd menu item</a>-->
        <!--              </a-menu-item>-->
        <!--            </a-menu>-->
        <!--          </template>-->
        <!--        </a-dropdown>-->
      </div>
    </div>
    <div class="body">
      <vxe-list
          :height="500"
          :data="state.list"
      >
        <template #default="{items}">
          <div class="text-white my-2 flex" v-for="item in items" :key="item.id">
            <a-checkbox v-model:checked="item.checked"></a-checkbox>
            <div class="flex-1 mx-1">
              {{ item.name }}
            </div>
            <div class="action">
              <a-button primary size="small" class="mx-1" type="link" @click="edit(item)">修改</a-button>
              <a-button danger size="small" type="link" @click="remove(item.id)">删除</a-button>
            </div>
          </div>
        </template>

      </vxe-list>
    </div>

  </div>

  <a-modal
      v-model:open="modalState.visible"
      :title="modalState.title"
      :footer="null"
      width="50%"
      @afterClose="cancel"
      @cancel="cancel"
  >
    <a-form
        :model="formState"
        autocomplete="off"
        @finish="onFinish"
        :label-col="{ span: 8 }"
        :wrapper-col="{ span: 16 }"
    >
      <a-row>
        <a-col :span="8">
          <a-form-item
              label="任务名称"
              name="name"
              :rules="[{ required: true, message: '请输入任务名称' }]"
          >
            <a-input allow-clear placeholder="请输入任务名称" v-model:value="formState.name"/>
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item
              label="团队成员"
              name="teams"
          >
            <a-select
                allow-clear
                placeholder="请选择团队成员"
                v-model:value="formState.teams"
                mode="multiple"
            >
              <a-select-option v-for="item in teams" :key="item.id" value="item.id">{{ item.name }}</a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item
              label="任务开始时间"
              name="startTime"
          >
            <a-date-picker allow-clear format="YYYY-MM-DD HH:mm:ss" show-time placeholder="请选择开始时间"
                           v-model:value='formState.startTime'/>
          </a-form-item>
        </a-col>
      </a-row>
      <a-row>
        <a-col :span="8">
          <a-form-item
              label="任务结束时间"
              name="endTime"
          >
            <a-date-picker allow-clear format="YYYY-MM-DD HH:mm:ss" show-time placeholder="请选择结束时间"
                           v-model:value='formState.endTime'/>
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item
              label="相关属性"
              name="relevantAttribute"
          >
            <a-select
                allow-clear
                placeholder="请选择相关属性"
                v-model:value="formState.relevantAttribute"
            >
              <a-select-option value="力量">力量</a-select-option>
              <a-select-option value="学识">学识</a-select-option>
              <a-select-option value="魅力">魅力</a-select-option>
              <a-select-option value="耐力">耐力</a-select-option>
              <a-select-option value="活力">活力</a-select-option>
              <a-select-option value="创造">创造</a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item
              label="重复方式"
              name="repeatType"
          >
            <a-select
                allow-clear
                placeholder="请选择重复方式"
                v-model:value="formState.repeatType"
            >
              <a-select-option value="1">每天一次</a-select-option>
              <a-select-option value="2">每周一次</a-select-option>
              <a-select-option value="3">每月一次</a-select-option>
              <a-select-option value="4">其他</a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
      </a-row>
      <a-row>
        <a-col :span="8">
          <a-form-item
              label="提醒方式"
              name="remindType"
          >
            <a-select
                allow-clear
                placeholder="请选择提醒方式"
                v-model:value="formState.remindType"
            >
              <a-select-option value="1">期限前10分钟</a-select-option>
              <a-select-option value="2">期限前30分钟</a-select-option>
              <a-select-option value="3">期限前1小时</a-select-option>
              <a-select-option value="4">其他</a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item
              label="重要程度"
              name="importentLevel"
          >
            <a-select
                allow-clear
                v-model:value="formState.importentLevel"
                placeholder="请选择重要程度"
            >
              <a-select-option value="1">一般</a-select-option>
              <a-select-option value="2">重要</a-select-option>
              <a-select-option value="3">非常重要</a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item
              label="紧急程度"
              name="urgentLevel"
          >
            <a-select
                allow-clear
                v-model:value="formState.urgentLevel"
                placeholder="请选择紧急程度"
            >
              <a-select-option value="1">一般</a-select-option>
              <a-select-option value="2">紧急</a-select-option>
              <a-select-option value="3">非常紧急</a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
      </a-row>
      <a-row>
        <a-col :span="8">
          <a-form-item
              label="奖励"
              name="rewards"
          >
            <a-input allow-clear placeholder="请输入奖励" v-model:value="formState.rewards"/>
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item
              label="惩罚"
              name="punishment"
          >
            <a-input allow-clear placeholder="请输入惩罚" v-model:value="formState.punishment"/>
          </a-form-item>
        </a-col>

      </a-row>
      <a-row>
        <a-col :span="8">
          <a-form-item
              label="添加子任务"
              name="childrenTasks"
          >
            <a-input allow-clear v-model:value="formState.childrenTaskName">
              <template #suffix>
                <plus-outlined @click="addChildrenTasks" class="cursor-pointer"/>
              </template>
            </a-input>
          </a-form-item>
          <a-form-item label="子任务" v-if="formState.childrenTasks.length>0">
            <a-input style="margin-bottom: 5px" allow-clear v-model:value="item.label"
                     v-for="item in formState.childrenTasks"
                     :key="item.label"/>
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item
              label="任务描述"
              name="description"
          >
            <a-textarea allow-clear placeholder="请输入任务描述" v-model:value="formState.description"/>
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item class="flex justify-end text-right">
        <a-button class="mx-2" @click="cancel">取消</a-button>
        <a-button type="primary" html-type="submit">确认</a-button>
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<style scoped lang="scss">
.to-do-list {
  color: #fff;
  padding: 20px;
  position: fixed;
  top: 0;
  right: 0;
  width: 300px;
  overflow: auto;
  background: rgba(0, 0, 0, 0.5);
  border-radius: 10px;

  .anticon-plus, .anticon-more {
    font-size: 20px;
  }

  .action {
    display: none;
  }

  .text-white {
    height: 30px;

    &:hover .action {
      display: block;
    }
  }

  .ant-btn-sm {
    padding: 0;
  }
}
</style>
