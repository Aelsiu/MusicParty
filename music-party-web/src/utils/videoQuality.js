export const VIDEO_QUALITY_KEY = 'mp_video_quality_preference';
export const AUTO_VIDEO_QUALITY = Object.freeze({ version: 1, mode: 'auto' });

export function normalizeVideoQuality(value) {
    if (value?.version === 1 && value.mode === 'manual' && /^q\d{1,4}$/.test(value.qualityId)) {
        return { version: 1, mode: 'manual', qualityId: value.qualityId };
    }
    return { ...AUTO_VIDEO_QUALITY };
}

export function readVideoQuality(storage = globalThis.localStorage) {
    try { return normalizeVideoQuality(JSON.parse(storage.getItem(VIDEO_QUALITY_KEY))); }
    catch { return { ...AUTO_VIDEO_QUALITY }; }
}

export function saveVideoQuality(value, storage = globalThis.localStorage) {
    try { storage.setItem(VIDEO_QUALITY_KEY, JSON.stringify(normalizeVideoQuality(value))); } catch { /* Private browsing/storage disabled. */ }
}
