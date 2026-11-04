<template>
  <div>
    <!-- VIP 入口卡（U7）：VIP 标识/到期时间，或开通按钮跳 /vip -->
    <a-card v-if="vipEntry.visible" class="vip-entry" :bordered="false" @click="goVip">
      <div class="vip-entry-inner">
        <template v-if="vipEntry.vip">
          <span class="vip-entry-badge">VIP</span>
          <span class="vip-entry-text">
            VIP 会员<span v-if="vipEntry.expireTime"> · 到期时间：{{ vipEntry.expireTime }}</span>
          </span>
        </template>
        <template v-else>
          <span class="vip-entry-badge vip-entry-badge-normal">VIP</span>
          <span class="vip-entry-text">升级 VIP，解锁高级列 / AI Agent / 更多配额</span>
        </template>
        <span class="vip-entry-action">{{ vipEntry.vip ? '查看权益' : '开通 VIP' }}</span>
      </div>
    </a-card>
    <a-table
      rowKey="id"
      :columns="columns"
      :dataSource="state.list.filter((item) => item.noteType >= 2)"
      :loading="!state.isFinished"
      :pagination="state.pagination"
      @resizeColumn="handleResizeColumn"
      :scroll="{ y: 650 }"
      :show-expand-column="false"
    >
      <template #bodyCell="{ column, record, text }">
        <template v-if="column.dataIndex === 'title'">
          <a-button type="link" class="cursor-pointer" @click="click(record)">
            {{ text }}
          </a-button>
        </template>
      </template>
    </a-table>
  </div>
</template>
<script lang="tsx">
import { onBeforeMount, reactive, ref, watch } from "vue";
import { useJump, usePagination } from "../../hooks";
import { useRouter } from "vue-router";
import { getVipInfo } from "../../api/vip";

export default {
  setup() {
    const { click } = useJump();
    const router = useRouter();

    // VIP 入口卡数据（U7）：按需拉取，管理员（豁免限额）不展示入口卡
    const vipEntry = reactive({
      visible: false,
      vip: false,
      expireTime: "" as string | null,
    });
    const loadVipEntry = async () => {
      try {
        const info = await getVipInfo();
        if (info.code === 200 && !info.status?.admin) {
          vipEntry.visible = true;
          vipEntry.vip = !!info.status?.vip;
          vipEntry.expireTime = info.status?.expireTime || null;
        }
      } catch {
        // 拉取失败不阻塞页面，仅不显示入口卡
      }
    };
    const goVip = () => {
      router.push("/vip");
    };
   
    const { state, getData } = usePagination({
      url: "system/note/list",
    });

    onBeforeMount(() => {
      getData();
      loadVipEntry();
    });
    const columns = ref([
      {
        title: "文件名称",
        dataIndex: "title",
        key: "title",
        resizable: true,
        width: 500,
      },
      {
        title: "描述",
        dataIndex: "remark",
        key: "remark",
        resizable: true,
        width: 500,
      },
    ]);

    const handleResizeColumn = (w, col) => {
      col.width = w;
    };

    return {
      click,
      state,
      columns,
      handleResizeColumn,
      vipEntry,
      goVip,
    };
  },
};
</script>
<style scoped lang="scss">
.vip-entry {
  margin-bottom: 16px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid #f0e6cf;

  &:hover {
    border-color: #e8a33d;
  }

  .vip-entry-inner {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .vip-entry-badge {
    padding: 2px 10px;
    border-radius: 6px;
    background: linear-gradient(135deg, #f7c948, #e8a33d);
    color: #fff;
    font-weight: 700;
    letter-spacing: 1px;
  }

  .vip-entry-badge-normal {
    background: #fafafa;
    color: #d48806;
    border: 1px solid #e8a33d;
  }

  .vip-entry-text {
    font-size: 14px;
  }

  .vip-entry-action {
    margin-left: auto;
    color: #d48806;
    font-weight: 600;
  }
}

:deep(.ant-table) {
  .ant-table-body {
    height: 650px;
  }
}
</style>