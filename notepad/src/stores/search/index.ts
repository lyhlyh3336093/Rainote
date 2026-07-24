
import { defineStore } from 'pinia'
import { reactive, toRefs } from 'vue'

export const useSearchStore = defineStore('search', () => {
    const searchState = reactive({
        title: '',
        dateRange: [],
        description: ''
    })
    return {
        ...toRefs(searchState)
    }
})
