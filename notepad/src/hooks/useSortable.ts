import Sortable from 'sortablejs';


export interface Sortable {
    el?: HTMLElement;
    options?: any;
}

const useSortable = (props: Sortable) => {
    const {el, options} = props;
    const instance = Sortable.create(el, {
        animation: 200,
        ...options,
    });
    return {
        instance
    };
}
export default useSortable;