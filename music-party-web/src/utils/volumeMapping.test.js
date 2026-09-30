import test from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_VOLUME, clampVolume, volumeToGain, gainToVolume, readSavedVolume } from './volumeMapping.js';
test('单滑杆线性覆盖 0–100%，最大对应原线性音量的 50%', () => {
    assert.equal(volumeToGain(0), 0);
    assert.equal(volumeToGain(0.25), 0.125);
    assert.equal(volumeToGain(0.5), 0.25);
    assert.equal(volumeToGain(1), 0.5);
    for (let percent = 0; percent <= 100; percent++) {
        const volume = percent / 100;
        assert(Math.abs(gainToVolume(volumeToGain(volume)) - volume) < 1e-12);
    }
});
test('保持已保存的实际输出及静音，超过新上限的旧音量截断到 50%', () => {
    assert.equal(readSavedVolume(null), DEFAULT_VOLUME);
    for (const gain of [0, 0.01, 0.1, 0.25, 0.5]) {
        assert(Math.abs(volumeToGain(readSavedVolume(String(gain))) - gain) < 1e-12);
    }
    for (const gain of [0.65, 1, 2]) {
        assert.equal(readSavedVolume(String(gain)), 1);
        assert.equal(volumeToGain(readSavedVolume(String(gain))), 0.5);
    }
});
test('音量保存和刷新往返保持一致，非法数据及越界输入不突破新上限', () => {
    for (const volume of [0, 0.1, 0.5, 1]) {
        assert(Math.abs(readSavedVolume(String(volumeToGain(volume))) - volume) < 1e-12);
    }
    for (const value of ['', ' ', 'invalid', 'Infinity']) {
        assert.equal(readSavedVolume(value), DEFAULT_VOLUME);
    }
    for (const value of [NaN, Infinity, undefined]) {
        assert.equal(clampVolume(value), DEFAULT_VOLUME);
        assert.equal(gainToVolume(value), DEFAULT_VOLUME);
    }
    assert.equal(clampVolume(-1), 0);
    assert.equal(clampVolume(2), 1);
    assert.equal(volumeToGain(-1), 0);
    assert.equal(volumeToGain(3), 0.5);
    assert.equal(readSavedVolume('-1'), 0);
});
