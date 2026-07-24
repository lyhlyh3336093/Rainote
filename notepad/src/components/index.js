import * as Icon from '@ant-design/icons-vue';
import NpInput from './Input/index.vue';
import NpSelect from './Select/index.vue';
import NpPicker from './Date/index.vue';
import NpCheckbox from './Checkbox/index.vue';
import NpUpload from './Upload/index.vue';
import NpMath from './Math/index.vue';

const modulesFiles = [NpInput, NpSelect, NpPicker, NpCheckbox, NpUpload,NpMath];
const withInstall = (Vue) => {
    for (const [key, component] of Object.entries(Icon)) {
        if (!Vue._context.components[key]) {
            Vue.component(key, component)
        }
    }

    modulesFiles.forEach(file => {
        Vue.component(file.name, file);
    });
}
export default withInstall;
export {
    NpMath,
    NpInput,
    NpSelect,
    NpPicker,
    NpCheckbox,
}