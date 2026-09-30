const handlers = new Map();

export function registerBackHandler(priority, handle) {
    const id = Symbol();
    handlers.set(id, { priority, handle });
    return () => handlers.delete(id);
}

export function handleModalBack() {
    for (const { handle } of [...handlers.values()].sort((a, b) => b.priority - a.priority)) {
        if (handle()) return true;
    }
    return false;
}
