import { computed, nextTick, onMounted, onBeforeUnmount, watch } from 'vue';
import { registerBackHandler } from '../services/backNavigation';
import { createGuideIntro } from '../utils/guideIntro';
import { visibleGuideRect } from '../utils/guideGeometry';
import { getMainGuidePoints } from '../utils/guideContent';
import { getAdminGuidePoints } from '../utils/adminGuideContent';

export function useGuideController({ guide, player, user, admin, room, mainEnabled, adminEnabled, blocked }) {
    let storage;
    try { storage = globalThis.localStorage; } catch { /* Browser storage may be unavailable. */ }
    const intro = createGuideIntro(storage);
    let focus, context, frame, observer, alive = true;
    const help = () => [...document.querySelectorAll('[data-guide="help"]')].find(element => visibleGuideRect(element));
    const otherDialog = () => [...document.querySelectorAll('[role="dialog"],[aria-modal="true"]')]
        .some(element => !element.closest('[data-guide-overlay]') && element !== document.querySelector('[data-guide-scope="admin"]') && visibleGuideRect(element));
    const enabled = scene => (scene === 'admin' ? adminEnabled() : mainEnabled()) && !blocked(scene) && !otherDialog();
    const points = computed(() => guide.scene === 'intro' ? [{ id: 'help', title: '帮助入口', summary: '有问题，随时来这里看看。', items: ['点 HELP 查看功能说明。'], selector: '[data-guide="help"]' }]
        : guide.scene === 'admin' ? getAdminGuidePoints({ player }) : getMainGuidePoints({ player, user, room }));
    function remember() { focus = document.activeElement; context = { generation: room.generation, mode: player.mediaMode }; }
    function close(userAction = true) {
        if (!guide.active) return false;
        if (userAction && guide.scene === 'intro') intro.complete();
        const previous = context;
        guide.close(); context = null;
        const original = focus; focus = null;
        nextTick(() => {
            if (!guide.active && original?.isConnected && previous?.generation === room.generation
                && user.isAuthPassed && !blocked('main') && !otherDialog()) original.focus?.({ preventScroll: true });
        });
        return true;
    }
    function openMain() {
        if (!enabled('main') || !help() || guide.active) return false;
        remember(); intro.complete(); return guide.open('main');
    }
    function openAdmin() {
        if (!enabled('admin') || guide.active) return false;
        remember(); return guide.open('admin');
    }
    function startMain() {
        if (guide.scene !== 'intro' || !enabled('main') || !help()) return close(false);
        intro.complete(); guide.open('main'); return true;
    }
    function queueIntro() {
        if (frame || !alive || guide.active || intro.hasSeen() || !player.synchronized || document.visibilityState === 'hidden') return;
        frame = window.requestAnimationFrame(async () => {
            frame = null; await nextTick();
            if (!alive || guide.active || intro.hasSeen() || !player.synchronized || document.visibilityState === 'hidden' || !enabled('main') || !help()) return;
            remember(); guide.open('intro');
        });
    }
    function reconcile() {
        if (guide.active && (!context || context.generation !== room.generation || context.mode !== player.mediaMode || !enabled(guide.scene))) close(false);
        queueIntro();
    }
    const stop = watch(() => [room.generation, player.mediaMode, player.synchronized, user.isAuthPassed,
        admin.isVerified, admin.showDashboard, mainEnabled(), adminEnabled(), blocked(guide.scene)], reconcile, { flush: 'post' });
    const unregister = registerBackHandler(200, () => close());
    onMounted(() => {
        document.addEventListener('visibilitychange', reconcile); window.addEventListener('resize', reconcile);
        if (typeof MutationObserver !== 'undefined') {
            observer = new MutationObserver(records => { if (records.some(record => !record.target.closest?.('[data-guide-overlay]'))) reconcile(); });
            observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'hidden'] });
        }
        queueIntro();
    });
    onBeforeUnmount(() => {
        alive = false; stop(); unregister(); observer?.disconnect(); window.cancelAnimationFrame(frame);
        document.removeEventListener('visibilitychange', reconcile); window.removeEventListener('resize', reconcile); guide.close();
    });
    return { points, openMain, openAdmin, startMain, close };
}
