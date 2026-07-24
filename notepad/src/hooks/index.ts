import useCookie from "./useCookie";
import useFetch from './useFetch'
import useLockFn from './useLockFn';
import usePagination from './usePagination';
import useSortable from './useSortable';
import useJump from "./useJump";
export {
    useJump,
    useFetch,
    useCookie,
    useLockFn,
    useSortable,
    usePagination
}


// @ts-ignore
// let files :Files= import.meta.globEager('./*');
// export const hooks={};
// for (const file of Object.keys(files)) {
//     const name = file.split('/').pop().split('.ts')[0];
//     hooks[name] = files[file].default;
// }
//

