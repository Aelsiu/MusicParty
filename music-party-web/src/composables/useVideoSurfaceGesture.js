import { watch, onBeforeUnmount } from 'vue';

const excluded = '[data-video-interaction],button,input,select,textarea,a,[contenteditable], [role=menu], [role=slider]';
export const isVideoInteraction = event => (event.composedPath?.() || [event.target]).some(node => node?.matches?.(excluded));

export function useVideoSurfaceGesture({ context, allowed = () => true, doubleAllowed = allowed,
    intent = () => undefined, onSingle, onDouble, now = () => performance.now(), schedule = setTimeout, cancel = clearTimeout }) {
    let down = null, pending = null, timer, alive = true;
    const pointers = new Set();
    const same = captured => captured.every((value, index) => Object.is(value, context()[index]));
    function reset() { cancel(timer); timer = undefined; pending = down = null; }
    function single() {
        const candidate = pending; pending = null; cancel(timer); timer = undefined;
        if (alive && candidate && same(candidate.context) && allowed()) onSingle(candidate.intent);
    }
    function pointerDown(event) {
        pointers.add(event.pointerId);
        if (pointers.size > 1 || event.isPrimary === false || event.button !== 0 || isVideoInteraction(event) || !allowed()) { reset(); return; }
        down = { id: event.pointerId, x: event.clientX, y: event.clientY, time: now(), distance: 0, context: context() };
    }
    function pointerMove(event) {
        if (!down || event.pointerId !== down.id) return;
        down.distance = Math.max(down.distance, Math.hypot(event.clientX - down.x, event.clientY - down.y));
        if (down.distance > 10) reset();
    }
    function pointerUp(event) {
        pointers.delete(event.pointerId);
        const click = down; down = null;
        if (!click || click.id !== event.pointerId) return;
        const time = now();
        if (time - click.time > 300 || Math.max(click.distance, Math.hypot(event.clientX-click.x,event.clientY-click.y)) > 10
            || !same(click.context) || isVideoInteraction(event) || !allowed()) { reset(); return; }
        if (pending && same(pending.context) && time < pending.deadline
            && Math.hypot(event.clientX-pending.x,event.clientY-pending.y) <= 24) {
            reset(); if (doubleAllowed()) onDouble(); return;
        }
        if (pending) single();
        pending = { x: event.clientX, y: event.clientY, context: context(), intent: intent(), deadline: time + 300 };
        timer = schedule(single, 300);
    }
    function pointerCancel(event) { pointers.delete(event.pointerId); reset(); }
    function keyDown(event) {
        if (event.repeat || !['Enter',' '].includes(event.key) || isVideoInteraction(event) || !doubleAllowed()) return;
        event.preventDefault(); reset(); onDouble();
    }
    watch(context, (value, previous) => { if (!previous || value.some((item,index) => !Object.is(item,previous[index]))) reset(); }, { flush: 'sync' });
    onBeforeUnmount(() => { alive = false; reset(); pointers.clear(); });
    return { pointerDown, pointerMove, pointerUp, pointerCancel, keyDown, reset };
}
