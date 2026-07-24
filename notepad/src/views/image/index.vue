<template>
  <template v-if="fileList.length > 0">
    <div class="flex flex-col items-center file">
      <div class="file-action flex items-center opacity-0">
        <a-tooltip>
          <template #title>下载文件</template>
          <CloudDownloadOutlined @click="downloadFile(fileList[0].originalFilename, fileList[0].url);"
            class="flex items-center justify-center border border-solid border-gray-200 rounded px-2 py-1" />
        </a-tooltip>
        <a-tooltip>
          <template #title>修改文件</template>
          <FormOutlined @click="fileList = []"
            class="flex items-center justify-center border border-solid border-gray-200 rounded px-2 py-1" />
        </a-tooltip>
      </div>
      <template v-if="isImage">
        <div v-viewer="options" class="w-full img">
          <template v-for="{ url, originalFilename } in fileList" :key="url">
            <img class="cursor-zoom-in" :src="url" :data-source="url" :alt="originalFilename" />
          </template>
        </div>
      </template>
      <div v-else class="flex items-center p-5 border border-solid border-gray-400">
        <file-outlined style="font-size: 25px; color: #1890ff; margin-right: 10px" />
        {{ fileList[0].originalFilename }}
      </div>
    </div>
  </template>
  <div v-else class="file-upload">
    <div v-if="cache.length" class="flex items-center justify-center opacity-0">
      <a-tooltip>
        <template #title>取消修改</template>
        <CloseOutlined
          class="flex items-center justify-center border border-solid border-gray-200 rounded px-2 py-1 m-2"
          @click="fileList = cache" />
      </a-tooltip>
    </div>
    <a-upload-dragger name="file" list-type="picture" :action="action" @change="handleChange" withCredentials
      :accept="type === 'image' ? 'image/*' : '*'" :headers="headers">
      <p class="ant-upload-drag-icon">
        <inbox-outlined></inbox-outlined>
      </p>
      <p class="ant-upload-text">
        单击或拖动{{ type === "image" ? "图片" : "视频或文件" }}到此区域进行上传
      </p>
    </a-upload-dragger>
  </div>
</template>
<script lang="ts">
import { InboxOutlined, FileOutlined, CloudDownloadOutlined, FormOutlined, CloseOutlined } from "@ant-design/icons-vue";
import { defineComponent, reactive, ref, toRefs, computed, } from "vue";
import { message, UploadChangeParam } from "ant-design-vue";
import { useStore } from "../../stores/editor";
import { useCookie } from "../../hooks";
import { directive } from "v-viewer";
import "viewerjs/dist/viewer.css";
import { storeToRefs } from "pinia";
import { downloadFile } from "@/components/editorjs/utils";

export default defineComponent({
  methods: { downloadFile },
  props: {
    type: {
      type: String,
      default: "image",
    },
    blockId: {
      type: [String, Number],
      default: "",
    },
    data: {
      type: Object,
      default() {
        return {};
      },
    },
  },
  components: {
    FileOutlined,
    InboxOutlined,
    FormOutlined,
    CloseOutlined,
    CloudDownloadOutlined
  },
  directives: {
    viewer: directive(),
  },
  setup({ type, blockId, data }) {
    const store = useStore();
    const { id } = storeToRefs(store);
    const fileList = ref([]);
    const cache = ref([]);


    if (data?.url) {
      fileList.value = [data];
      cache.value = [data];
    }
    const state = reactive({
      options: {
        toolbar: true,
        url: "data-source",
      },
    });

    const isImage = computed(
      () =>
        type === "image" ||
        /jpg|png|svg|gif|webp/gi.test(fileList.value[0]?.originalFilename)
    );
    const handleChange = (info: UploadChangeParam) => {
      const status = info.file.status;
      if (status !== "uploading") {
        console.log(info.file, info.fileList);
      }
      if (status === "done") {
        const { response } = info.file;
        if (response.code === 200) {
          const fileInfo = {
            url: response.url,
            originalFilename: response.originalFilename,
          };
          store[type][id.value][blockId] = fileInfo;
          fileList.value = [fileInfo];
          cache.value = [fileInfo];
        } else {
          message.error(response.msg);
        }
      } else if (status === "error") {
        message.error(`${info.file.name} 上传失败`);
      }
    };
    const cookie = useCookie;
    let token = cookie.get("token");
    const headers = {
      Authorization: token,
    };
    const action = window.location.origin ===
      "http://www.rainote.cn" ?
      "http://www.rainote.cn:8089/common/upload" : '/api/common/upload'
    return {
      cache,
      action,
      isImage,
      ...toRefs(state),
      headers,
      handleChange,
      fileList,
      CloseOutlined
    };
  },
});
</script>

<style scoped lang="scss">
:deep(.ant-upload) {
  height: auto;
}

:deep(.ant-upload-list-item-thumbnail) {
  display: flex;
  align-items: center;
  justify-content: center;
}

.file-action {
  span {
    margin: 0 5px;
  }
}

.file {
  &:hover {
    .file-action {
      opacity: 1 !important;
    }
  }
}

.file-upload {
  &:hover {
    .opacity-0 {
      opacity: 1 !important;
    }
  }

}
</style>