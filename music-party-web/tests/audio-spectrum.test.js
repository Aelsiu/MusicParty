import assert from 'node:assert/strict';
import { test } from 'node:test';
import { AudioSpectrum } from '../src/logic/AudioSpectrum.js';

class FakeNode {
    connections = new Set();
    connect(node) { this.connections.add(node); }
    disconnect(node) {
        if (node) this.connections.delete(node);
        else this.connections.clear();
    }
}

class FakeContext {
    state = 'running';
    sampleRate = 48000;
    destination = {};
    source = new FakeNode();
    analyser = Object.assign(new FakeNode(), {
        frequencyBinCount: 256,
        reads: 0,
        floatReads: 0,
        getByteFrequencyData(buffer) {
            this.reads++;
            buffer.fill(128);
        },
        getFloatFrequencyData(buffer) {
            this.floatReads++;
            buffer.fill(-85 + 60 * 128 / 255);
        }
    });
    createAnalyser() { return this.analyser; }
    createMediaElementSource(audio) { this.audio = audio; return this.source; }
    close() { this.state = 'closed'; return Promise.resolve(); }
}

globalThis.window = { AudioContext: FakeContext };
const createSpectrum = () => {
    const spectrum = new AudioSpectrum();
    spectrum.attach({ paused: false, ended: false, readyState: 4 });
    return spectrum;
};

test('analysis is a separate tap and disabling it keeps audio output connected', () => {
    const spectrum = createSpectrum();
    const { source, analyser, context } = spectrum;
    assert.deepEqual([...source.connections], [context.destination]);
    spectrum.setActive(true);
    assert.ok(source.connections.has(analyser));
    spectrum.setActive(false);
    assert.deepEqual([...source.connections], [context.destination]);
    spectrum.readBands(100);
    assert.equal(analyser.reads, 0);
    assert.equal(analyser.floatReads, 0);
    spectrum.dispose();
});

test('frequency sampling is capped at 30 Hz and reuses the same buffers', () => {
    const spectrum = createSpectrum();
    spectrum.setActive(true);
    const buffer = spectrum.bins;
    const floatBuffer = spectrum.floatBins;
    const columns = spectrum.bars;
    const bands = spectrum.readBands(0);
    assert.equal(spectrum.readBands(16), bands);
    assert.equal(spectrum.analyser.reads, 1);
    assert.equal(spectrum.analyser.floatReads, 1);
    spectrum.readBands(34);
    assert.equal(spectrum.analyser.reads, 2);
    assert.equal(spectrum.analyser.floatReads, 2);
    assert.equal(spectrum.bins, buffer);
    assert.equal(spectrum.floatBins, floatBuffer);
    assert.equal(spectrum.bars, columns);
    for (const energy of bands) assert.ok(Math.abs(energy - 128 / 255) < 0.00001);
    for (const energy of columns) assert.ok(Math.abs(energy - 0.17372739) < 0.00001);
    spectrum.dispose();
});

test('pausing or buffering returns zero energy without reading the FFT', () => {
    const spectrum = createSpectrum();
    spectrum.setActive(true);
    spectrum.readBands(0);
    spectrum.audio.paused = true;
    assert.deepEqual([...spectrum.readBands(100)], [0, 0, 0]);
    assert.ok(spectrum.bars.every(value => value === 0));
    spectrum.audio.paused = false;
    spectrum.audio.readyState = 1;
    assert.deepEqual([...spectrum.readBands(200)], [0, 0, 0]);
    assert.ok(spectrum.bars.every(value => value === 0));
    assert.equal(spectrum.analyser.reads, 1);
    assert.equal(spectrum.analyser.floatReads, 1);
    spectrum.dispose();
});

test('ring columns distinguish low and high frequencies and join smoothly at the seam', () => {
    const spectrum = createSpectrum();
    spectrum.setActive(true);
    let peakBin = 1;
    spectrum.analyser.getFloatFrequencyData = (buffer) => {
        buffer.fill(-Infinity);
        buffer[peakBin] = -15;
    };
    spectrum.readBands(0);
    const lowPeak = spectrum.bars.indexOf(Math.max(...spectrum.bars));
    peakBin = 50;
    spectrum.readBands(34);
    const highPeak = spectrum.bars.indexOf(Math.max(...spectrum.bars));
    assert.ok(highPeak > lowPeak + 5);
    assert.ok(spectrum.bars.some(value => value > 0));
    for (let i = 0; i < spectrum.bars.length / 2; i++) {
        assert.equal(spectrum.bars[i], spectrum.bars[spectrum.bars.length - 1 - i]);
    }
    spectrum.dispose();
});

test('strong ring frequencies retain their level differences while ribbon bytes stay saturated', () => {
    const spectrum = createSpectrum();
    spectrum.setActive(true);
    spectrum.analyser.getByteFrequencyData = buffer => buffer.fill(255);
    let db = -25;
    spectrum.analyser.getFloatFrequencyData = buffer => buffer.fill(db);
    const levels = [];
    for (const [i, value] of [-25, -20, -15].entries()) {
        db = value;
        assert.deepEqual([...spectrum.readBands(i * 34)], [1, 1, 1]);
        assert.ok(spectrum.bars.every(value => value > 0 && value < 1));
        levels.push(spectrum.bars[0]);
    }
    for (const [i, expected] of [0.60881071, 0.69975173, 0.79535927].entries()) {
        assert.ok(Math.abs(levels[i] - expected) < 0.00001);
    }
    assert.ok(levels[0] < levels[1] && levels[1] < levels[2]);
    spectrum.dispose();
});

test('a sparse high-frequency tone is attenuated by its band width without amplifying silence', () => {
    const spectrum = createSpectrum();
    spectrum.setActive(true);
    spectrum.analyser.getFloatFrequencyData = buffer => {
        buffer.fill(-Infinity);
        buffer[100] = -20;
    };
    spectrum.readBands(0);
    // The final band covers 18 bins at 48 kHz; one -20 dB tone among silence
    // has a band level of -20 - 10 * log10(18), about -32.55 dB.
    assert.ok(Math.abs(spectrum.bars[29] - 0.48066225) < 0.00001);
    assert.equal(spectrum.bars[30], spectrum.bars[29]);
    assert.ok(spectrum.bars.slice(0, 29).every(value => value === 0));
    assert.ok(spectrum.bars.slice(31).every(value => value === 0));
    spectrum.dispose();
});

test('silence and nonfinite frequencies stay at zero, with bounded near-floor and strong levels', () => {
    const spectrum = createSpectrum();
    spectrum.setActive(true);
    let db;
    spectrum.analyser.getFloatFrequencyData = buffer => buffer.fill(db);
    let now = 0;
    for (const value of [-Infinity, Infinity, NaN, -100, -85, -80]) {
        db = value;
        spectrum.readBands(now);
        assert.ok(spectrum.bars.every(value => value === 0));
        now += 34;
    }
    db = -79;
    spectrum.readBands(now);
    assert.ok(spectrum.bars.every(value => value > 0 && value < 0.002));
    for (const value of [-5, 0, 6]) {
        now += 34;
        db = value;
        spectrum.readBands(now);
        assert.ok(spectrum.bars.every(value => value === 1));
    }
    spectrum.dispose();
});

test('replacing the audio element closes the old graph and dispose releases the new graph', () => {
    const spectrum = createSpectrum();
    spectrum.setActive(true);
    const oldContext = spectrum.context;
    const oldSource = spectrum.source;
    const nextAudio = { paused: false, readyState: 4 };
    spectrum.attach(nextAudio);
    assert.equal(oldContext.state, 'closed');
    assert.equal(oldSource.connections.size, 0);
    assert.equal(spectrum.audio, nextAudio);
    const context = spectrum.context;
    spectrum.dispose();
    assert.equal(context.state, 'closed');
    assert.equal(spectrum.context, null);
    assert.equal(spectrum.audio, null);
    assert.deepEqual([...spectrum.readBands(100)], [0, 0, 0]);
});
