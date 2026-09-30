export const DEFAULT_VOLUME = 0.1;

export function clampVolume(value) {
    return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : DEFAULT_VOLUME;
}

// 滑杆使用平方曲线，保留最大输出，并让中低音量区间更容易调整
export function volumeToGain(volume) {
    return clampVolume(volume) ** 2;
}

export function gainToVolume(gain) {
    return Math.sqrt(clampVolume(gain));
}

export function readSavedVolume(savedGain) {
    if (savedGain === null || savedGain.trim() === '') return DEFAULT_VOLUME;
    const gain = Number(savedGain);
    return Number.isFinite(gain) ? gainToVolume(gain) : DEFAULT_VOLUME;
}
