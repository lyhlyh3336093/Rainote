let lock = false

function useLockFn(fn: (...args) => Promise<any>) {
    return async (...args) => {
        if (lock) return;
        lock = true;
        try {
            const ret = await fn(...args);
            lock = false;
            return ret;
        } catch (e) {
            lock = false;
            throw e;
        }
    }

}

export default useLockFn;