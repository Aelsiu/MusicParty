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
        getByteFrequencyData(buffer) {
            this.reads++;
            buffer.fill(128);
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
    spectrum.dispose();
});

test('frequency sampling is capped at 30 Hz and reuses the same buffers', () => {
    const spectrum = createSpectrum();
    spectrum.setActive(true);
    const buffer = spectrum.bins;
    const bands = spectrum.readBands(0);
    assert.equal(spectrum.readBands(16), bands);
    assert.equal(spectrum.analyser.reads, 1);
    spectrum.readBands(34);
    assert.equal(spectrum.analyser.reads, 2);
    assert.equal(spectrum.bins, buffer);
    for (const energy of bands) assert.ok(Math.abs(energy - 128 / 255) < 0.00001);
    spectrum.dispose();
});

test('pausing or buffering returns zero energy without reading the FFT', () => {
    const spectrum = createSpectrum();
    spectrum.setActive(true);
    spectrum.readBands(0);
    spectrum.audio.paused = true;
    assert.deepEqual([...spectrum.readBands(100)], [0, 0, 0]);
    spectrum.audio.paused = false;
    spectrum.audio.readyState = 1;
    assert.deepEqual([...spectrum.readBands(200)], [0, 0, 0]);
    assert.equal(spectrum.analyser.reads, 1);
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
