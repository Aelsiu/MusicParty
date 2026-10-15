import { computed, ref, onBeforeUnmount } from 'vue';

// A single interaction clock controls both the surface bar and its room dock.
export function useVideoControlsVisibility({ mobile = false, now = () => performance.now(),
    schedule = setTimeout, cancel = clearTimeout, touchDelay = 3000 } = {}) {
    const visible = ref(true), pointerInside = ref(false), focusWithin = ref(false);
    const menuOpen = ref(false), dragging = ref(false), externalLock = ref(false), guideHold = ref(false);
    const locked = computed(() => focusWithin.value || menuOpen.value || dragging.value || externalLock.value);
    const isMobile = () => typeof mobile === 'function' ? mobile() : mobile;
    let timer, generation = 0, revealUntil = 0, alive = true, mouseOutside = false, mouseActivity = false;
    function clear() { cancel(timer); timer = undefined; generation++; }
    function recalculate() {
        clear();
        if (!alive) return;
        // Guide points need the bar even after mouse leave or an expired idle clock.
        // Keep the original interaction clock so closing the guide resumes it.
        if (guideHold.value) { visible.value = true; return; }
        // Explicitly leaving the surface starts hiding even while a closing menu or
        // a mouse-created focus lock is still being released by its owner.
        if (mouseOutside) { visible.value = false; return; }
        if (locked.value && !mouseActivity) { visible.value = true; return; }
        const remaining = revealUntil - now();
        if (remaining <= 0) { visible.value = false; return; }
        visible.value = true;
        const current = generation;
        timer = schedule(() => { if (alive && current === generation) recalculate(); }, remaining);
    }
    function reveal() { revealUntil = now() + touchDelay; recalculate(); }
    const mouseEvent = event => event?.pointerType !== 'touch' && (event?.pointerType === 'mouse' || !isMobile());
    function pointerEnter(event) {
        if (!mouseEvent(event)) return false;
        mouseOutside = false; mouseActivity = true; pointerInside.value = true; reveal(); return true;
    }
    function pointerMove(event) {
        if (!mouseEvent(event)) return false;
        mouseOutside = false; mouseActivity = true; pointerInside.value = true; reveal(); return true;
    }
    function pointerLeave(event) {
        if (!mouseEvent(event)) return false;
        mouseOutside = true; mouseActivity = true; pointerInside.value = false; revealUntil = 0; recalculate(); return true;
    }
    function keyboardActivity() { mouseOutside = false; mouseActivity = false; reveal(); }
    function tapBlank(intent) {
        if (!isMobile()) { reveal(); return; }
        // A touch interaction can take over after a mouse leaves a hybrid device.
        mouseOutside = false; mouseActivity = false;
        if ((intent === 'hide' || intent === undefined && visible.value) && !locked.value) { revealUntil = 0; recalculate(); }
        else reveal();
    }
    function setLock(target, value) {
        const wasLocked = locked.value; target.value = !!value;
        if (wasLocked && !locked.value && !mouseOutside && !mouseActivity) revealUntil = now() + touchDelay;
        recalculate();
    }
    const setFocusWithin = value => setLock(focusWithin, value);
    const setMenuOpen = value => setLock(menuOpen, value);
    const setDragging = value => setLock(dragging, value);
    const setExternalLock = value => setLock(externalLock, value);
    function setGuideHold(value) { guideHold.value = !!value; recalculate(); }
    function reset() {
        clear(); pointerInside.value = focusWithin.value = menuOpen.value = dragging.value = externalLock.value = guideHold.value = false;
        mouseOutside = false; mouseActivity = false; revealUntil = 0; visible.value = false;
    }
    onBeforeUnmount(() => { reset(); alive = false; });
    reveal();
    return { visible, locked, pointerEnter, pointerMove, pointerLeave, tapBlank, reveal, keyboardActivity, reset,
        setFocusWithin, setMenuOpen, setDragging, setExternalLock, setGuideHold };
}
