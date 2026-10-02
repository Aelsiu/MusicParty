// Resolve only the room in the address bar; remembered credentials never choose another room.
export async function resolveRoomEntry(id, { api, managerToken = '', admission = null, now = Date.now(), discardAdmission = () => {} }) {
    if (!id) return { kind: 'security' };
    const room = await api.access(id);
    if (managerToken) {
        try { return { kind: 'owner', room: await api.manage(id) }; }
        catch (e) { if (e.response?.status !== 403) throw e; }
    }
    if (admission?.token && admission.expiresAt > now) {
        try {
            const restored = await api.resume(id, admission.token);
            return { kind: 'member', room: restored.room, token: admission.token, expiresAt: restored.expiresAt };
        } catch (e) {
            if (e.response?.status !== 403) throw e;
            discardAdmission();
        }
    }
    if (room.publicRoom) {
        try { return { kind: 'member', ...await api.publicAdmission(id) }; }
        catch (e) { if (e.response?.status !== 403) throw e; }
    }
    return { kind: 'code', room };
}
