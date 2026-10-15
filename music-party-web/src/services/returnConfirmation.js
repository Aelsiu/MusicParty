// The confirmation belongs only to the room navigation RETURN action.
export function createReturnConfirmation({ animation, confirm, publish = () => {},
    now = () => performance.now(), setTimer = setTimeout, clearTimer = clearTimeout }) {
    let phase = 'idle', deadline = 0, timer = null, generation = 0;
    const setPhase = value => { phase = value; publish(value); };
    function cancelTimer() { if (timer !== null) clearTimer(timer); timer = null; }
    function reset() {
        generation++; cancelTimer(); deadline = 0;
        animation.reset('RETURN'); setPhase('idle');
    }
    function restore() {
        cancelTimer(); setPhase('restoring');
        const current = generation;
        animation.animate('SURE??', 'RETURN', 220, () => { if (current === generation) reset(); });
    }
    function click() {
        if (phase === 'armed') {
            if (now() >= deadline) { restore(); return 'expired'; }
            reset(); confirm(); return 'confirmed';
        }
        if (phase !== 'idle') return 'ignored';
        const current = ++generation;
        setPhase('scrambling');
        animation.animate('RETURN', 'SURE??', 280, () => {
            if (current !== generation) return;
            deadline = now() + 3000; setPhase('armed');
            timer = setTimer(() => { if (current === generation) restore(); }, 3000);
        });
        return 'arming';
    }
    return { click, reset };
}
