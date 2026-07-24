import { reactive,watch } from "vue";
import useFetch from "./useFetch";
import { useSearchStore } from "../stores/search/index.ts";
import { storeToRefs } from "pinia";

export interface Props {
    url: string;
    param?: any  ;
}


export default (props: Props) => {
    const { url, param = {} } = props;
    const { title, dateRange, description } = storeToRefs(useSearchStore());
    const state = reactive({
        list: [],
        pagination: {
            total: 0,
            pageNum: 1,
            pageSize: 10,
            showTotal: (total: any) => `共 ${total} 条`,
            showSizeChanger: true,
            showQuickJumper: false,
            showLessItems: true,
            onChange(page: any, pageSisze: any) {
                state.pagination.pageNum = page;
                state.pagination.pageSize = pageSisze;
                getData();
            }
        },
        isFinished: false,
    });
    // @ts-ignore
    const getData = async () => {
        const { pageNum, pageSize } = state.pagination;
        const params = {
            ...param,
            pageNum,
            pageSize,
            title: title.value,
            kssj: dateRange.value[0]||'',
            jssj: dateRange.value[1]||'',
            description: description.value
        }
        let queryString = Object.keys(params).map(key => key + '=' + params[key]).join('&');
        const { data, isFinished }: any = await useFetch(`${url}?${queryString}`).get().json();
        state.isFinished = isFinished;
        if (data?.value) {
            state.list = data.value.rows || data.value.data;
            state.pagination.total = data.value.total;
        }
    }
    const reset = () => {
        state.pagination.pageNum = 1;
        state.pagination.pageSize = 10;
        getData();
    }
    const updateList = (list: any) => {
        state.list = list;
    }

    watch([title, dateRange, description], () => {
        getData();
    })
    return {
        reset,
        state,
        getData,
        updateList
    }
}