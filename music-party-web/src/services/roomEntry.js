import { isPairingCode, normalizePairingCode } from '../utils/pairingCode.js';

// Resolve only the room in the address bar; remembered credentials never choose another room.
export async function resolveRoomEntry(id, { api, managerToken = '', admission = null, pairingCode,
    now = Date.now(), discardAdmission = () => {}, isCurrent = () => true }) {
    if (!id) return { kind: 'security' };
    const room = await api.access(id);
    if (!isCurrent()) return { kind: 'cancelled' };
    // A supplied invitation is the same explicit submission as the NEED CODE form.
    // Failure stays on that form even if old credentials or public access could enter.
    if (pairingCode !== undefined) {
        if (!isPairingCode(pairingCode)) return { kind: 'code', room, error: '请输入四位字母或数字配对码' };
        try {
            const joined = await api.joinRoom(id, normalizePairingCode(pairingCode));
            return isCurrent() ? { kind: 'member', ...joined } : { kind: 'cancelled' };
        } catch (e) {
            if (!isCurrent()) return { kind: 'cancelled' };
            if (!e.response || e.response.status >= 500) throw e;
            return { kind: 'code', room, error: e.response.data?.message || e.response.data?.detail || '配对码无效或已更新' };
        }
    }
    if (managerToken) {
        try {
            const managed = await api.manage(id);
            return isCurrent() ? { kind: 'owner', room: managed } : { kind: 'cancelled' };
        }
        catch (e) { if (e.response?.status !== 403) throw e; }
    }
    if (!isCurrent()) return { kind: 'cancelled' };
    if (admission?.token && admission.expiresAt > now) {
        try {
            const restored = await api.resume(id, admission.token);
            return isCurrent() ? { kind: 'member', room: restored.room, token: admission.token, expiresAt: restored.expiresAt } : { kind: 'cancelled' };
        } catch (e) {
            if (e.response?.status !== 403) throw e;
            if (!isCurrent()) return { kind: 'cancelled' };
            discardAdmission();
        }
    }
    if (!isCurrent()) return { kind: 'cancelled' };
    if (room.publicRoom) {
        try {
            const admitted = await api.publicAdmission(id);
            return isCurrent() ? { kind: 'member', ...admitted } : { kind: 'cancelled' };
        }
        catch (e) { if (e.response?.status !== 403) throw e; }
    }
    return { kind: 'code', room };
}
