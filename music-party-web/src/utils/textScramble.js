const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';

// Both room navigation actions use the same timed glyph substitution.
export function createTextScrambler({ publish, reducedMotion = () => false,
    now = () => performance.now(), requestFrame = callback => requestAnimationFrame(callback),
    cancelFrame = id => cancelAnimationFrame(id), random = Math.random }) {
    let frame = null, generation = 0;
    function cancel() {
        generation++;
        if (frame !== null) cancelFrame(frame);
        frame = null;
    }
    function reset(text) { cancel(); publish([{ text, dud: false }]); }
    function animate(from, to, duration, complete = () => {}) {
        cancel();
        const current = generation;
        if (reducedMotion()) { publish([{ text: to, dud: false }]); complete(); return; }
        const count = Math.max(from.length, to.length);
        const queue = Array.from({ length: count }, (_, index) => ({
            from: from[index] || ' ', to: to[index] || ' ', start: 16 + index * 5,
            finish: duration * .48 + index * duration * .52 / Math.max(1, count - 1),
            character: ALPHABET[Math.floor(random() * ALPHABET.length)]
        }));
        const started = now();
        let lastShuffle = -Infinity;
        function tick(time) {
            if (current !== generation) return;
            const elapsed = time - started;
            const shuffle = elapsed - lastShuffle >= 32;
            if (shuffle) lastShuffle = elapsed;
            publish(queue.map(item => {
                if (elapsed >= item.finish) return { text: item.to, dud: false };
                if (elapsed < item.start) return { text: item.from, dud: false };
                if (shuffle) item.character = ALPHABET[Math.floor(random() * ALPHABET.length)];
                return { text: item.character, dud: true };
            }));
            if (elapsed >= duration) {
                frame = null; publish([{ text: to, dud: false }]); complete();
            } else frame = requestFrame(tick);
        }
        frame = requestFrame(tick);
    }
    return { animate, reset, cancel };
}
