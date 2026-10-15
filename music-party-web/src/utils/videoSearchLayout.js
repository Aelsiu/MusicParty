const KEY = 'mp_video_search_layout';
export const normalizeVideoSearchLayout = value => value === 'poster' ? 'poster' : 'compact';
export function readVideoSearchLayout(storage = globalThis.localStorage) {
    try { return normalizeVideoSearchLayout(storage?.getItem(KEY)); } catch { return 'compact'; }
}
export function saveVideoSearchLayout(value, storage = globalThis.localStorage) {
    const layout = normalizeVideoSearchLayout(value);
    try { storage?.setItem(KEY, layout); } catch { /* Display preference still works when storage is unavailable. */ }
    return layout;
}
