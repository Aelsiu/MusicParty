const KEY = 'mp_danmaku_settings';
export const DEFAULT_DANMAKU_SETTINGS = Object.freeze({ area: 25, opacity: 50, fontSize: 70, speed: 1 });
export const DANMAKU_SPEEDS = Object.freeze([0.5, 0.75, 1, 1.25, 1.5]);
export const DANMAKU_SPEED_LABELS = Object.freeze(['极慢', '慢', '适中', '快', '极快']);

export function normalizeDanmakuSettings(value) {
    const settings = { ...DEFAULT_DANMAKU_SETTINGS };
    if (!value || typeof value !== 'object' || Array.isArray(value)) return settings;
    for (const [key, min, max, step] of [['area', 25, 100, 25], ['opacity', 0, 100, 5], ['fontSize', 50, 150, 5]]) {
        if (typeof value[key] === 'number' && Number.isFinite(value[key])) {
            settings[key] = Math.round(Math.min(max, Math.max(min, value[key])) / step) * step;
        }
    }
    if (DANMAKU_SPEEDS.includes(value.speed)) settings.speed = value.speed;
    return settings;
}

export function readDanmakuSettings(storage = globalThis.localStorage) {
    try { return normalizeDanmakuSettings(JSON.parse(storage?.getItem(KEY))); }
    catch { return { ...DEFAULT_DANMAKU_SETTINGS }; }
}

export function saveDanmakuSettings(value, storage = globalThis.localStorage) {
    const settings = normalizeDanmakuSettings(value);
    try { storage?.setItem(KEY, JSON.stringify(settings)); } catch { /* Keep the session preference when storage is unavailable. */ }
    return settings;
}

export const danmakuDuration = (entry, speed = 1) => (entry.mode === 4 || entry.mode === 5 ? 4 : 8) / speed;

// Both lane allocation and drawing use the same geometry, including fullscreen resizing.
export function danmakuLayout(height, value) {
    const settings = normalizeDanmakuSettings(value);
    const fullHeight = Math.max(1, Number(height) || 360), areaHeight = fullHeight * settings.area / 100;
    const fontSize = size => Math.min(Math.min(size, fullHeight / 14) * settings.fontSize / 100, areaHeight / 1.4);
    const laneCount = Math.max(1, Math.min(12, Math.floor(areaHeight / (fontSize(30) * 1.4 + 2))));
    return { laneCount, rowHeight: areaHeight / laneCount, fontSize, settings };
}
