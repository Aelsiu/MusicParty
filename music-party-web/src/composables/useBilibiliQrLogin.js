import { ref } from 'vue';
import QRCode from 'qrcode';
import { roomsApi } from '../api/rooms';

const terminal = new Set(['SUCCESS', 'EXPIRED', 'FAILED']);
export function useBilibiliQrLogin({ api = roomsApi, encode = url => QRCode.toDataURL(url, { width: 256, margin: 2, errorCorrectionLevel: 'M' }), schedule = setTimeout, unschedule = clearTimeout, now = Date.now } = {}) {
    const open = ref(false), image = ref(''), state = ref(''), error = ref(''), busy = ref(false);
    let generation = 0, task = null, taskContext = null, timer = null, deadline = 0, failures = 0;
    const cancel = (id, context) => { if (id) Promise.resolve(api.biliQrCancel(id, context)).catch(() => {}); };
    function close() {
        generation++; open.value = false; busy.value = false; image.value = ''; unschedule(timer); timer = null;
        cancel(task, taskContext); task = null; taskContext = null; deadline = 0;
    }
    async function start() {
        unschedule(timer); cancel(task, taskContext); task = null; taskContext = null; deadline = 0;
        const current = ++generation;
        const requestContext = api.biliQrContext?.();
        open.value = true; busy.value = true; image.value = ''; state.value = ''; error.value = ''; failures = 0;
        try {
            const result = await api.biliQrCreate(requestContext);
            if (current !== generation || !open.value) { cancel(result.task, requestContext); return; }
            task = result.task; taskContext = requestContext; deadline = Number(result.expiresAt);
            const rendered = await encode(result.url);
            if (current !== generation || !open.value) return;
            image.value = rendered; state.value = result.state;
            timer = schedule(() => poll(current), 2000);
        } catch (e) {
            if (current === generation) { state.value = 'FAILED'; error.value = e.response?.data?.message || '二维码创建失败，请重试'; cancel(task, taskContext); task = null; taskContext = null; }
        } finally { if (current === generation) busy.value = false; }
    }
    async function poll(current) {
        if (current !== generation || !open.value || !task) return;
        if (now() >= deadline) { state.value = 'EXPIRED'; image.value = ''; cancel(task, taskContext); task = null; taskContext = null; return; }
        try {
            const result = await api.biliQrCheck(task, taskContext);
            if (current !== generation || !open.value) return;
            failures = 0; error.value = ''; state.value = result.state;
            if (terminal.has(state.value)) { image.value = ''; return; }
        } catch (e) {
            if (current !== generation || !open.value) return;
            const status = e.response?.status;
            if ([401, 403, 404].includes(status) || ++failures >= 3) {
                state.value = 'FAILED'; image.value = ''; error.value = e.response?.data?.message || '登录状态查询失败，请重试';
                cancel(task, taskContext); task = null; taskContext = null; return;
            }
            error.value = '网络暂不可用，正在重试';
        }
        timer = schedule(() => poll(current), 2000);
    }
    return { open, image, state, error, busy, start, close };
}
