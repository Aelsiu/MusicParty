import test from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_VOLUME, volumeToGain, gainToVolume, readSavedVolume } from './volumeMapping.js';
test('双滑杆为线性音量，第一条最大对应原 50%，第二条最大对应原 100%', () => {
    assert.equal(volumeToGain(0),0);
    assert.equal(volumeToGain(1),0.5);
    assert.equal(volumeToGain(1.5),0.75);
    assert.equal(volumeToGain(2),1);
    for(let percent=0;percent<=200;percent++) {
        const volume=percent/100;
        assert(Math.abs(gainToVolume(volumeToGain(volume))-volume)<1e-12);
    }
});
test('首次默认 10%，两种旧映射保存的实际输出及静音均保持不变', () => {
    assert.equal(readSavedVolume(null),DEFAULT_VOLUME);
    for(const gain of [0,.01,.1,.25,.5,.65,1])assert(Math.abs(volumeToGain(readSavedVolume(String(gain)))-gain)<1e-12);
    assert.equal(readSavedVolume('0.65'),1.3);
});
test('音量保存、刷新和非法数据不会超出音频元素范围', () => {
    for(const volume of [0,.1,.5,1,1.5,2])assert(Math.abs(readSavedVolume(String(volumeToGain(volume)))-volume)<1e-12);
    for(const value of ['', ' ', 'invalid', 'Infinity'])assert.equal(readSavedVolume(value),DEFAULT_VOLUME);
    assert.equal(volumeToGain(-1),0);
    assert.equal(volumeToGain(3),1);
    assert.equal(readSavedVolume('-1'),0);
    assert.equal(readSavedVolume('2'),2);
});
