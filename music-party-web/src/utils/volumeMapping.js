export const DEFAULT_VOLUME = 0.2;

export function clampVolume(value) {
    return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : DEFAULT_VOLUME;
}

// 单条线性滑杆覆盖 0–100%，最大对应原线性音量的 50%
export function volumeToGain(volume) {
    return clampVolume(volume) / 2;
}

export function gainToVolume(gain) {
    return Number.isFinite(gain) ? Math.max(0, Math.min(0.5, gain)) * 2 : DEFAULT_VOLUME;
}

export function readSavedVolume(savedGain) {
    if (savedGain === null || savedGain.trim() === '') return DEFAULT_VOLUME;
    const gain = Number(savedGain);
    return Number.isFinite(gain) ? gainToVolume(gain) : DEFAULT_VOLUME;
}
