import test from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_VOLUME, volumeToGain, gainToVolume, readSavedVolume, clampVolume } from './volumeMapping.js';

test('音量曲线覆盖静音和最大输出，中段对应四分之一输出', () => {
    assert.equal(volumeToGain(0), 0);
    assert.equal(volumeToGain(0.5), 0.25);
    assert.equal(volumeToGain(1), 1);
    let previous = -1;
    for (let percent = 0; percent <= 100; percent++) {
        const volume = percent / 100;
        const gain = volumeToGain(volume);
        assert(gain > previous);
        assert(Math.abs(gainToVolume(gain) - volume) < 1e-12);
        previous = gain;
    }
    assert(volumeToGain(0.11) - volumeToGain(0.1) < volumeToGain(0.51) - volumeToGain(0.5));
});

test('首次使用默认显示 10%，已有音量记忆保留实际输出，包括静音', () => {
    assert.equal(readSavedVolume(null), DEFAULT_VOLUME);
    for (const oldGain of [0, 0.1, 0.25, 0.65, 1]) {
        const volume = readSavedVolume(String(oldGain));
        assert(Math.abs(volumeToGain(volume) - oldGain) < 1e-12);
    }
});

test('保存再读取无需反复迁移，异常音量数据不会传给音频元素', () => {
    for (const volume of [0, 0.1, 0.33, 0.5, 0.8, 1]) {
        assert(Math.abs(readSavedVolume(String(volumeToGain(volume))) - volume) < 1e-12);
    }
    for (const invalid of ['', ' ', 'invalid', 'NaN', 'Infinity']) {
        assert.equal(readSavedVolume(invalid), DEFAULT_VOLUME);
    }
    assert.equal(readSavedVolume('-1'), 0);
    assert.equal(readSavedVolume('2'), 1);
    assert.equal(clampVolume(NaN), DEFAULT_VOLUME);
    assert.equal(volumeToGain(-1), 0);
    assert.equal(volumeToGain(2), 1);
});
