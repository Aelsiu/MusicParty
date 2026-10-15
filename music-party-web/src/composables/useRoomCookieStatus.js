import { ref, watch, onBeforeUnmount } from 'vue';
import { roomsApi } from '../api/rooms';
import { roomSession } from '../services/roomSession';

export const cookieStatusTips = Object.freeze({
    UNCONFIGURED: '本房间未配置该平台cookie',
    EXPIRED: '本房间需更新该平台cookie',
    CONFIGURED: '本房间已配置该平台cookie'
});
const sameContext = (left, right) => left && right && ['roomId', 'managerToken', 'generation'].every(key => left[key] === right[key]);
const empty = () => ({ netease: null, bilibili: null });

export function useRoomCookieStatus({ active, api = roomsApi, session = roomSession, now = Date.now, cacheMs = 30000 } = {}) {
    const statuses = ref(empty()), loading = ref(false), error = ref('');
    let sequence = 0, controller, pending, cache, alive = true;
    const context = () => Object.freeze({ roomId: session.roomId, roomToken: session.roomToken,
        managerToken: session.managerToken, generation: session.generation });
    const eligible = () => alive && active() && session.roomId && session.managerToken;
    function cancel() {
        sequence++; controller?.abort(); controller = null; pending = null; loading.value = false;
    }
    function reset() { cancel(); statuses.value = empty(); error.value = ''; }
    function refresh(force = false) {
        if (!eligible()) return Promise.resolve();
        const captured = context();
        if (force) cache = null;
        if (!force && cache && sameContext(cache.context, captured) && now() < cache.expiresAt) {
            statuses.value = { ...cache.statuses }; error.value = ''; return Promise.resolve();
        }
        if (!force && pending && sameContext(pending.context, captured)) return pending.promise;
        cancel(); const request = sequence;
        controller = new AbortController(); loading.value = true; error.value = '';
        const current = () => eligible() && request === sequence && sameContext(captured, context());
        const promise = (async () => {
            try {
                const data = await api.cookieStatus(captured, { signal: controller.signal });
                if (!current()) return;
                if (!['netease', 'bilibili'].every(platform => Object.hasOwn(cookieStatusTips, data?.[platform]))) throw new Error('Invalid status');
                statuses.value = { netease: data.netease, bilibili: data.bilibili };
                cache = { context: captured, statuses: { ...statuses.value }, expiresAt: now() + cacheMs };
            } catch (e) {
                if (current() && e?.name !== 'AbortError' && e?.code !== 'ERR_CANCELED') error.value = '平台Cookie状态暂无法确认，请稍后重试';
            } finally {
                if (request === sequence) { loading.value = false; controller = null; pending = null; }
            }
        })();
        pending = { context: captured, promise }; return promise;
    }
    watch(() => [active(), session.roomId, session.managerToken, session.generation], () => {
        reset(); if (eligible()) refresh();
    }, { immediate: true });
    onBeforeUnmount(() => { alive = false; reset(); cache = null; });
    return { statuses, loading, error, refresh };
}
