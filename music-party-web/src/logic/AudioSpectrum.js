// 只读取当前 audio 的频谱；复用小缓冲区，不通过 Vue 响应式状态逐帧传数据。
export class AudioSpectrum {
    constructor() {
        this.context = null;
        this.source = null;
        this.analyser = null;
        this.audio = null;
        this.active = false;
        this.bands = new Float32Array(3);
        this.lastReadAt = -Infinity;
    }

    attach(audio) {
        this.dispose();
        const Context = window.AudioContext || window.webkitAudioContext;
        this.context = new Context();
        this.audio = audio;
        this.analyser = this.context.createAnalyser();
        this.analyser.fftSize = 512;
        this.analyser.smoothingTimeConstant = 0.72;
        this.analyser.minDecibels = -85;
        this.analyser.maxDecibels = -25;
        this.bins = new Uint8Array(this.analyser.frequencyBinCount);
        const binHz = this.context.sampleRate / this.analyser.fftSize;
        this.ranges = [40, 250, 2000, 10000].map(hz =>
            Math.min(this.bins.length, Math.max(1, Math.round(hz / binHz))));
        this.source = this.context.createMediaElementSource(audio);
        // 保持原音频直接输出，分析器仅作旁路，不改变音量或音质。
        this.source.connect(this.context.destination);
        this.resume();
    }

    resume() {
        if (this.context?.state === 'suspended') this.context.resume().catch(() => {});
    }

    setActive(active) {
        const next = !!active && !!this.source;
        if (next === this.active) return;
        if (next) this.source.connect(this.analyser);
        else this.source.disconnect(this.analyser);
        this.active = next;
        this.bands.fill(0);
        this.lastReadAt = -Infinity;
    }

    readBands(now) {
        if (!this.active || this.audio.paused || this.audio.ended
                || this.audio.readyState < 2 || this.context.state !== 'running') {
            this.bands.fill(0);
            return this.bands;
        }
        if (now - this.lastReadAt < 1000 / 30) return this.bands;
        this.lastReadAt = now;
        this.analyser.getByteFrequencyData(this.bins);
        for (let band = 0; band < 3; band++) {
            const start = this.ranges[band];
            const end = Math.max(start + 1, this.ranges[band + 1]);
            let energy = 0;
            for (let i = start; i < end; i++) energy += (this.bins[i] / 255) ** 2;
            this.bands[band] = Math.sqrt(energy / (end - start));
        }
        return this.bands;
    }

    dispose() {
        this.source?.disconnect();
        this.analyser?.disconnect();
        if (this.context && this.context.state !== 'closed') this.context.close().catch(() => {});
        this.context = null;
        this.source = null;
        this.analyser = null;
        this.audio = null;
        this.active = false;
        this.bands.fill(0);
    }
}

export const audioSpectrum = new AudioSpectrum();
