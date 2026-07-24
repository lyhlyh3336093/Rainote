<script setup lang="ts">
import {onMounted, reactive, ref, defineProps, defineEmits, nextTick, watch} from 'vue';
import {useFetch} from "@/hooks";
import {useUserStore} from "@/stores/user";
import {PlusOutlined} from '@ant-design/icons-vue'
import {Modal, message} from 'ant-design-vue'
import dayjs from 'dayjs';

const props = defineProps(['visible', 'date', 'row', 'type']);
const emits = defineEmits(['update:visible', 'finish']);
const teams = ref([]);
const formState = reactive({
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

});

const getTeams = async () => {
  const {data} = await useFetch('/system/task/teamMembers').get().json();
  teams.value = data.value.data;
}

const {userInfo} = useUserStore();


onMounted(() => {
  getTeams();
  if (props.date) {
    const date = dayjs(props.date.date);
    if (date.isValid()) {
      formState.startTime = date;
    } else {
      formState.startTime = dayjs();
    }
  }
  if (props.row) {
    const item = props.row;
    for (const itemKey in formState) {
      if (item[itemKey]) {
        if (itemKey === 'childrenTasks') {
          formState[itemKey] = item[itemKey].split(',');
        } else {
          formState[itemKey] = item[itemKey];
        }
        if (['startTime', 'endTime'].includes(itemKey)) {
          formState[itemKey] = dayjs(item[itemKey])
        }
      }
    }
  }
})
const onFinish = async (values) => {
  let url = 'system/task/add';
  if (props.type === 'add') {
    url = 'system/task/add';
  } else {
    values.id = props.row.id;
    url = 'system/task/edit';
  }
  if (formState.childrenTasks.length > 0) {
    values.parentTask = props.row.id;
  }
  if (values.startTime) values.startTime = new Date(values.startTime).getTime();
  if (values.endTime) values.endTime = new Date(values.endTime).getTime();

  values.childrenTasks = values.childrenTasks.map(item => item.label).toString();
  values.teams = values.teams.toString();
  values.leader = userInfo.user.userId;
  values.taskType = 0;
  const {data} = await useFetch(url).post(values).json();
  if (data?.value?.code === 200) {
    message.success('操作成功');
    emits('update:visible', false);
    emits('finish');
  }
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
    <a-form-item class="flex justify-end text-right" style="width:220px">
      <a-button class="mx-2" @click="emits('update:visible',false)">取消</a-button>
      <a-button type="primary" html-type="submit">确认</a-button>
    </a-form-item>
  </a-form>
</template>

<style scoped lang="scss">
</style>
