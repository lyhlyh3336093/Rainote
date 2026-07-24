<template>
      <div class="add-page">
        <a-dropdown :teleported="false" size="large" trigger="click" type="primary" @command="addPage">
          <a-button size="large">
            新建页面
          </a-button>
          <template #dropdown>
            <a-dropdown-menu>
              <a-dropdown-item command="docx">文档</a-dropdown-item>
            </el-dropdown-menu>
          </template>
        </a-dropdown>
      </div>
</template>

<script setup>
import {useRouter} from 'vue-router';

const router = useRouter();
const addPage = (item) => {
  const {href} = router.resolve({
    name: item,
    params: {
      id: +new Date()
    }
  });
  window.open(href, '_blank');
}
</script>
<script>
export default {
  name: "Operations"
}
</script>

<style lang="scss" scoped>
.app-title {
  .el-button {
    padding: 10px 20px;
  }
}
.add-page {
  text-align: center;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;

  .el-dropdown, .el-button {
    width: 100%;
  }

  .el-dropdown {
    margin: 0 20px;

    :deep(.el-popper) {
      width: 100%;

      .el-popper__arrow {
        display: none;
      }
    }
  }
}
</style>