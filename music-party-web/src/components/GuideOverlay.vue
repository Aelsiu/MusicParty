<script setup>
import { computed, ref, nextTick, onMounted, onBeforeUnmount, watch } from 'vue';
import { CircleHelp } from 'lucide-vue-next';
import { viewportRect, visibleGuideRect, intersectRect, placeGuideCard, placeGuideMarkers } from '../utils/guideGeometry';

const props = defineProps({ scene: String, points: { type: Array, default: () => [] } });
const emit = defineEmits(['close', 'start', 'unavailable']);
const panel = ref(null), card = ref(null), exitButton = ref(null);
const bounds = ref(viewportRect()), visible = ref([]), selected = ref(null), placement = ref(null);
const ripples = ref({});
const isAdmin = computed(() => props.scene === 'admin'), isIntro = computed(() => props.scene === 'intro');
const activePoint = computed(() => visible.value.find(point => point.id === selected.value));
let observer, resizeObserver, frame, leaveTimer, alive = true, drag, suppressClick = false, keyboardInput = true;
const scopeElement = () => isAdmin.value ? document.querySelector('[data-guide-scope="admin"]') : null;
const scrollElement = () => document.querySelector('[data-guide-scroll="admin"]');
const scopeStyle = computed(() => ({ left: `${bounds.value.left}px`, top: `${bounds.value.top}px`, width: `${bounds.value.width}px`, height: `${bounds.value.height}px` }));
const pointStyle = point => ({ left: `${point.x - bounds.value.left}px`, top: `${point.y - bounds.value.top}px`, '--guide-delay': `${-(point.index % 7) * .43}s` });
const cardStyle = computed(() => placement.value ? { left: `${placement.value.left - bounds.value.left}px`, top: `${placement.value.top - bounds.value.top}px`, width: `${placement.value.width}px`, maxHeight: `${placement.value.maxHeight}px` } : {});

function schedule() {
    if (frame || !alive) return;
    frame = window.requestAnimationFrame(() => { frame = null; measure(); });
}
async function positionCard() {
    await nextTick();
    if (!alive || !activePoint.value || !card.value) { placement.value = null; return; }
    // Keep the sticky exit hint separate from card content, including fallback.
    const top = isIntro.value ? bounds.value.top : Math.min(bounds.value.bottom - 1, bounds.value.top + 68);
    const bottom = isIntro.value ? Math.max(top + 1, bounds.value.bottom - 68) : bounds.value.bottom;
    const cardBounds = { ...bounds.value, top, bottom, height: bottom - top };
    const width = Math.max(1, Math.min(300, cardBounds.width - 32));
    // First measure at the desired width, avoiding a stale narrow card height.
    card.value.style.width = `${width}px`;
    card.value.style.maxHeight = `${Math.max(1, cardBounds.height - 24)}px`;
    const measured = card.value.getBoundingClientRect();
    placement.value = placeGuideCard(activePoint.value, { width, height: measured.height || 180 }, cardBounds);
}
function measure() {
    if (!alive) return;
    const viewport = viewportRect(), scope = scopeElement();
    const nextBounds = isAdmin.value ? scope && intersectRect(scope.getBoundingClientRect(), viewport) : viewport;
    if (!nextBounds) { emit('unavailable'); return; }
    const resized = ['left', 'top', 'width', 'height'].some(key => bounds.value[key] !== nextBounds[key]);
    bounds.value = nextBounds;
    const targets = props.points.flatMap((point, index) => {
        const elements = [...document.querySelectorAll(point.selector)];
        const match = elements.map(element => ({ element, rect: visibleGuideRect(element, nextBounds) })).find(match => match.rect);
        return match ? [{ ...point, ...match.rect, element: match.element, index }] : [];
    });
    const exitRect = exitButton.value?.getBoundingClientRect();
    visible.value = isIntro.value ? targets : placeGuideMarkers(targets, nextBounds, exitRect ? [exitRect] : []);
    if (isIntro.value) {
        if (!visible.value.length) { emit('unavailable'); return; }
        selected.value = visible.value[0].id;
    } else if (!activePoint.value) selected.value = null;
    positionCard();
    if (resized) nextTick(schedule);
}
function cancelLeave() { clearTimeout(leaveTimer); }
function select(id) { cancelLeave(); selected.value = id; positionCard(); }
function enter(point, event) {
    if (event.pointerType && event.pointerType !== 'mouse') return;
    select(point.id);
    ripples.value = { ...ripples.value, [point.id]: (ripples.value[point.id] || 0) + 1 };
}
function leave(event) {
    if (isIntro.value || event?.pointerType === 'touch') return;
    cancelLeave(); leaveTimer = setTimeout(() => { selected.value = null; }, 180);
}
function focusPoint(point) { if (keyboardInput) select(point.id); }
function clickPoint(point, event) {
    if (suppressClick) { suppressClick = false; return; }
    if (event.pointerType === 'mouse') { select(point.id); return; }
    selected.value = selected.value === point.id && event.detail !== 0 ? null : point.id;
    positionCard();
}
function pointerDown(event) {
    suppressClick = false;
    if (event.pointerType !== 'touch') return;
    drag = { id: event.pointerId, x: event.clientX, y: event.clientY, top: scrollElement()?.scrollTop || 0, moved: false };
    event.currentTarget.setPointerCapture?.(event.pointerId);
}
function pointerMove(event) {
    if (!drag || drag.id !== event.pointerId) return;
    if (Math.hypot(event.clientX - drag.x, event.clientY - drag.y) > 8) drag.moved = true;
    if (drag.moved && isAdmin.value) {
        const scroll = scrollElement();
        if (scroll) scroll.scrollTop = drag.top + drag.y - event.clientY;
    }
}
function pointerUp() { if (drag?.moved) suppressClick = true; drag = null; }
function wheel(event) {
    if (!isAdmin.value) return;
    const scroll = scrollElement();
    if (scroll) { event.preventDefault(); scroll.scrollTop += event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? scroll.clientHeight : 1); }
}
function onScroll(event) {
    if (event.target === scrollElement()) selected.value = null;
    if (!card.value?.contains(event.target)) schedule();
}
function keydown(event) {
    keyboardInput = true;
    if (event.key === 'Escape') { event.preventDefault(); event.stopImmediatePropagation(); emit('close'); return; }
    if (event.key === 'Tab') {
        const buttons = [...panel.value.querySelectorAll('button,[tabindex="0"]')];
        const first = buttons[0], last = buttons.at(-1);
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
        return;
    }
    if (!panel.value?.contains(event.target)) { event.preventDefault(); event.stopImmediatePropagation(); exitButton.value?.focus(); return; }
    if (isAdmin.value && !card.value?.contains(event.target) && ['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End'].includes(event.key)) {
        const scroll = scrollElement();
        if (!scroll) return;
        event.preventDefault();
        if (event.key === 'Home') scroll.scrollTop = 0;
        else if (event.key === 'End') scroll.scrollTop = scroll.scrollHeight;
        else scroll.scrollTop += (event.key.endsWith('Up') ? -1 : 1) * (event.key.startsWith('Page') ? scroll.clientHeight * .8 : 48);
    }
}
function focusin(event) { if (!panel.value?.contains(event.target)) exitButton.value?.focus({ preventScroll: true }); }
function clickOutside(event) {
    keyboardInput = false;
    if (isIntro.value || panel.value?.contains(event.target)) return;
    selected.value = null;
}
watch(() => props.scene, () => { selected.value = null; schedule(); }, { flush: 'post' });
watch(() => props.points, schedule, { flush: 'post' });
watch(selected, positionCard);
onMounted(async () => {
    measure(); await nextTick(); exitButton.value?.focus({ preventScroll: true });
    window.addEventListener('resize', schedule);
    document.addEventListener('scroll', onScroll, true);
    document.addEventListener('keydown', keydown, true);
    document.addEventListener('focusin', focusin, true);
    document.addEventListener('pointerdown', clickOutside, true);
    if (typeof MutationObserver !== 'undefined') {
        observer = new MutationObserver(records => { if (records.some(record => !panel.value?.contains(record.target))) schedule(); });
        const root = isAdmin.value ? scopeElement() : document.querySelector('[data-guide-business]');
        if (root) observer.observe(root, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style', 'hidden', 'data-guide-obscured'] });
    }
    if (typeof ResizeObserver !== 'undefined') { resizeObserver = new ResizeObserver(schedule); if (scopeElement()) resizeObserver.observe(scopeElement()); }
});
onBeforeUnmount(() => {
    alive = false; cancelLeave(); observer?.disconnect(); resizeObserver?.disconnect(); window.cancelAnimationFrame(frame);
    window.removeEventListener('resize', schedule); document.removeEventListener('scroll', onScroll, true);
    document.removeEventListener('keydown', keydown, true); document.removeEventListener('focusin', focusin, true); document.removeEventListener('pointerdown', clickOutside, true);
});
</script>

<template>
  <Teleport to="body">
    <section ref="panel" class="guide-overlay" :class="{ 'guide-admin': isAdmin, 'guide-intro': isIntro }" :style="scopeStyle" role="dialog" aria-modal="true" aria-label="功能引导" data-guide-overlay>
      <div class="guide-mask" @pointerdown="selected = isIntro ? selected : null"></div>
      <button ref="exitButton" type="button" class="guide-exit" @click="emit('close')">按ESC或点击此处退出引导</button>
      <template v-if="!isIntro">
        <button v-for="point in visible" :key="point.id" :data-guide-point="point.id" class="guide-bubble" :class="{ 'is-selected': selected === point.id }" :style="pointStyle(point)" type="button"
          :aria-label="`查看${point.title}说明`" :aria-expanded="selected === point.id" :aria-controls="selected === point.id ? 'guide-description' : undefined"
          @pointerenter="enter(point, $event)" @pointerleave="leave" @focus="focusPoint(point)" @click="clickPoint(point, $event)"
          @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="pointerUp" @pointercancel="pointerUp" @wheel="wheel">
          <span class="guide-dot" aria-hidden="true"></span>
          <span v-if="ripples[point.id]" :key="ripples[point.id]" class="guide-ripple" aria-hidden="true"><i></i><i></i></span>
        </button>
      </template>
      <button v-else-if="activePoint" class="guide-intro-help" :style="{ left: `${activePoint.element.getBoundingClientRect().left - bounds.left}px`, top: `${activePoint.element.getBoundingClientRect().top - bounds.top}px`, width: `${activePoint.element.getBoundingClientRect().width}px`, height: `${activePoint.element.getBoundingClientRect().height}px` }" type="button" aria-label="查看功能引导" @click="emit('start')"><CircleHelp aria-hidden="true" /></button>
      <article v-if="activePoint" id="guide-description" ref="card" class="guide-card bg-surface border border-medical-200 p-4 shadow-xl chamfer-br flex flex-col gap-3" :style="cardStyle" tabindex="0" @pointerenter="cancelLeave" @pointerleave="leave">
        <h2>{{ activePoint.title }}</h2>
        <p class="guide-summary">{{ activePoint.summary }}</p>
        <ul v-if="activePoint.items?.length" class="guide-details">
          <li v-for="item in activePoint.items" :key="item">{{ item }}</li>
        </ul>
        <button v-if="isIntro" type="button" class="guide-understood" @click="emit('close')">知道了</button>
      </article>
    </section>
  </Teleport>
</template>

<style scoped>
.guide-overlay{position:fixed;z-index:200;isolation:isolate;overflow:hidden;color:rgb(var(--medical-900));font-family:inherit}
.guide-mask{position:absolute;inset:0;background:rgb(0 0 0 / .52)}
.guide-admin{pointer-events:none}.guide-admin .guide-mask{pointer-events:none}
.guide-exit{position:absolute;top:max(12px,env(safe-area-inset-top));left:50%;transform:translateX(-50%);z-index:3;pointer-events:auto;max-width:calc(100% - 24px);padding:10px 16px;min-height:44px;border:1px solid rgb(var(--accent) / .5);border-radius:6px;background:rgb(var(--surface) / .96);color:rgb(var(--medical-900));font-size:12px;white-space:nowrap;box-shadow:0 2px 12px #0003}
.guide-intro .guide-exit{top:auto;bottom:max(12px,env(safe-area-inset-bottom))}
.guide-bubble{position:absolute;width:44px;height:44px;transform:translate(-50%,-50%);display:grid;place-items:center;border:0;background:transparent;padding:0;pointer-events:auto;cursor:pointer;touch-action:none;z-index:1;border-radius:50%}
.guide-dot{width:12px;height:12px;border-radius:50%;background:rgb(var(--accent));box-shadow:0 0 8px rgb(var(--accent) / .4);animation:guide-breathe 3s ease-in-out var(--guide-delay) infinite}
.guide-bubble.is-selected .guide-dot{animation-play-state:paused;opacity:1}
.guide-ripple{position:absolute;width:12px;height:12px;pointer-events:none}.guide-ripple i{position:absolute;inset:0;border:1px solid rgb(var(--accent));border-radius:50%;animation:guide-ripple .42s ease-out forwards}.guide-ripple i+i{animation-delay:.06s}
.guide-card{position:absolute;z-index:2;box-sizing:border-box;pointer-events:auto;overflow:auto;overscroll-behavior:contain;line-height:1.625;font-size:14px;overflow-wrap:anywhere}
.guide-card h2{margin:0;padding-bottom:8px;border-bottom:1px solid rgb(var(--medical-100));font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;font-weight:700;color:rgb(var(--accent))}
.guide-summary{margin:0;font-weight:700;color:rgb(var(--medical-900))}
.guide-details{margin:0;padding-left:18px;list-style:disc;color:rgb(var(--medical-600))}.guide-details li+li{margin-top:8px}
.guide-intro-help{position:absolute;z-index:2;pointer-events:auto;display:grid;place-items:center;color:rgb(var(--accent));border:2px solid rgb(var(--accent));border-radius:4px;background:rgb(var(--surface));box-shadow:0 0 0 5px rgb(var(--accent) / .2)}.guide-intro-help svg{width:18px;height:18px}
.guide-understood{display:block;margin:12px 0 0 auto;min-height:40px;padding:8px 16px;border:0;border-radius:4px;background:rgb(var(--accent));color:rgb(var(--medical-50));font-weight:700}
.guide-overlay button:focus-visible,.guide-card:focus-visible{outline:2px solid rgb(var(--accent));outline-offset:2px}
.guide-card:focus-visible{outline-offset:-3px}
@keyframes guide-breathe{0%,100%{transform:scale(.92);opacity:.65}50%{transform:scale(1.08);opacity:1}}
@keyframes guide-ripple{0%{transform:scale(1);opacity:.85}100%{transform:scale(2.65);opacity:0}}
@media(prefers-reduced-motion:reduce){.guide-dot{animation:none;opacity:1}.guide-ripple{display:none}}
</style>
