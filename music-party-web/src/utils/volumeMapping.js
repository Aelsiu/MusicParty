export const DEFAULT_VOLUME = 0.1;

export function clampVolume(value) {
    return Number.isFinite(value) ? Math.max(0, Math.min(2, value)) : DEFAULT_VOLUME;
}

// 两条线性滑杆覆盖 0–200%，200% 对应音频元素原有的最大输出
export function volumeToGain(volume) {
    return clampVolume(volume) / 2;
}

export function gainToVolume(gain) {
    return Number.isFinite(gain) ? Math.max(0, Math.min(1, gain)) * 2 : DEFAULT_VOLUME;
}

export function readSavedVolume(savedGain) {
    if (savedGain === null || savedGain.trim() === '') return DEFAULT_VOLUME;
    const gain = Number(savedGain);
    return Number.isFinite(gain) ? gainToVolume(gain) : DEFAULT_VOLUME;
}
