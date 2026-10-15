import { computed, onScopeDispose, ref, toValue, watch } from 'vue';

// Preparation follows an absolute monotonic deadline, independent of play intent.
export function useVideoCountdown(transition, {
    now = () => performance.now(), schedule = callback => requestAnimationFrame(callback), cancel = id => cancelAnimationFrame(id)
} = {}) {
    const remainingMs = ref(0), durationMs = ref(3000);
    let frame = null, deadline = 0;
    const stop = () => { if (frame !== null) cancel(frame); frame = null; };
    const draw = () => {
        frame = null;
        remainingMs.value = Math.max(0, Math.min(durationMs.value, deadline - now()));
        if (remainingMs.value > 0) frame = schedule(draw);
    };
    watch(() => [toValue(transition)?.deadlineMonotonic, toValue(transition)?.durationMs], ([end, duration]) => {
        stop();
        if (!Number.isFinite(end) || !(duration > 0)) { remainingMs.value = 0; return; }
        deadline = end; durationMs.value = duration; draw();
    }, { immediate: true });
    onScopeDispose(stop);
    return { remainingMs, fraction: computed(() => remainingMs.value / durationMs.value) };
}
