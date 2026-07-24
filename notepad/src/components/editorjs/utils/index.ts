import { message } from "ant-design-vue";
import NProgress from 'nprogress';
export const downloadFile = async (name, path) => {
    message.success('开始下载');
    if (!NProgress.isRendered()) {
        NProgress.start();
    }
    fetch(path).then((res) => {
        res.blob().then((blob) => {
            NProgress.done();
            const blobUrl = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = blobUrl;
            a.download = name;
            a.click();
            window.URL.revokeObjectURL(blobUrl);
        });
    });

}