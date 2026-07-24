import { ref, watch } from 'vue';
import useFetch from './useFetch'
import { useDebounceFn } from "@vueuse/core";
const useSearch = () => {
    const value = ref('');
    const list = ref([]);
    const onChange = async(v) => {
        const { data } = await useFetch(`/system/note/list?pageNum=1&pageSize=10&title=${v}`).get().json();
        list.value = data.value.data;
    };
    const change = useDebounceFn(onChange, 500);
    watch(() => value.value, async v => {
        change(v);
    })
    return {
        list,
        value,
    }
}
export default useSearch;