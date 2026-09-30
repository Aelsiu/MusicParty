// 只读取当前 audio 的频谱；复用小缓冲区，不通过 Vue 响应式状态逐帧传数据。
export class AudioSpectrum {
    constructor() {
        this.context = null;
        this.source = null;
        this.analyser = null;
        this.audio = null;
        this.active = false;
        this.bands = new Float32Array(3);
        this.bars = new Float32Array(60);
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
        this.floatBins = new Float32Array(this.analyser.frequencyBinCount);
        const binHz = this.context.sampleRate / this.analyser.fftSize;
        this.ranges = [40, 250, 2000, 10000].map(hz =>
            Math.min(this.bins.length, Math.max(1, Math.round(hz / binHz))));
        // 半圈按对数频率分配，另一半镜像衔接，避免圆环首尾突然跳变。
        const half = this.bars.length / 2;
        this.barRanges = new Uint16Array(half + 1);
        for (let i = 0; i <= half; i++) {
            this.barRanges[i] = Math.min(this.bins.length - 1, Math.max(1, Math.round(50 * (10000 / 50) ** (i / half) / binHz)));
        }
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
        this.bars.fill(0);
        this.lastReadAt = -Infinity;
    }

    readBands(now) {
        if (!this.active || this.audio.paused || this.audio.ended
                || this.audio.readyState < 2 || this.context.state !== 'running') {
            this.bands.fill(0);
            this.bars.fill(0);
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
        // 圆形读取同一次 FFT 的完整分贝值，避免强音在 byte 转换时被截成相同高度。
        // 丝带继续使用上面的 byte 数据，保留原来的响应。
        this.analyser.getFloatFrequencyData(this.floatBins);
        const half = this.bars.length / 2;
        for (let bar = 0; bar < half; bar++) {
            const start = this.barRanges[bar];
            const end = Math.max(start + 1, this.barRanges[bar + 1]);
            let power = 0;
            for (let i = start; i < end; i++) {
                const decibels = this.floatBins[i];
                if (Number.isFinite(decibels)) power += 10 ** (decibels / 10);
            }
            const decibels = power > 0 ? 10 * Math.log10(power / (end - start)) : -Infinity;
            // -80dB 以下留在底部；强音保留层次，只有接近 -5dB 才触及峰值。
            const level = Math.max(0, Math.min(1, (decibels + 80) / 75)) ** 1.6;
            this.bars[bar] = this.bars[this.bars.length - 1 - bar] = level;
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
        this.bars.fill(0);
    }
}

export const audioSpectrum = new AudioSpectrum();
