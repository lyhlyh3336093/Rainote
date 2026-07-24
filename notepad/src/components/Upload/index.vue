<template>
    <!-- <a-upload name="file">
        <a-button>
            <upload-outlined></upload-outlined>
        </a-button>
    </a-upload> -->
    <div></div>
</template>
<script lang="tsx">
import { defineComponent } from "vue";
import { UploadOutlined } from "@ant-design/icons-vue";

export default defineComponent({
    name: 'np-upload',
    components: { UploadOutlined },
    setup() { },
});
</script>
