import { reactive } from 'vue';
import { navigateRoom } from './roomRoute.js';

const saved = (() => { try { return JSON.parse(sessionStorage.getItem('mp_management_session') || 'null'); } catch { return null; } })();
export const roomSession = reactive({ roomId: '', roomName: '', roomToken: '', ownerAccess: false,
    shareEnabled: false, generation: 0, shareRevision: 0,
    managerToken: saved?.token || '', licenseId: saved?.licenseId || '', root: saved?.root || false });
export function saveManager(value) {
    roomSession.managerToken = value.token;
    roomSession.licenseId = value.licenseId;
    roomSession.root = value.root;
    sessionStorage.setItem('mp_management_session', JSON.stringify(value));
}
export function clearManager() {
    roomSession.managerToken = ''; roomSession.licenseId = ''; roomSession.root = false;
    sessionStorage.removeItem('mp_management_session');
}
export function selectRoom(room, token = '', owner = false) {
    ++roomSession.generation; ++roomSession.shareRevision;
    roomSession.roomId = room.id; roomSession.roomName = room.name; roomSession.roomToken = token; roomSession.ownerAccess = owner;
    roomSession.shareEnabled = room.shareEnabled === true;
    sessionStorage.setItem('mp_active_room', room.id);
    localStorage.setItem('mp_last_room', room.id);
    navigateRoom(room.id);
}
export function clearRoom(navigate = true) {
    ++roomSession.generation; ++roomSession.shareRevision;
    roomSession.roomId = ''; roomSession.roomName = ''; roomSession.roomToken = ''; roomSession.ownerAccess = false;
    roomSession.shareEnabled = false;
    sessionStorage.removeItem('mp_active_room');
    if (navigate) navigateRoom();
}
export function syncRoomShare(enabled, roomId = roomSession.roomId, generation = roomSession.generation,
    shareRevision = roomSession.shareRevision) {
    if (typeof enabled !== 'boolean' || !roomId || roomId !== roomSession.roomId || generation !== roomSession.generation) return false;
    if (shareRevision !== roomSession.shareRevision && enabled !== roomSession.shareEnabled) return false;
    if (roomSession.shareEnabled !== enabled) ++roomSession.shareRevision;
    roomSession.shareEnabled = enabled;
    globalThis.window?.dispatchEvent(new CustomEvent('musicparty:share', { detail: { enabled, roomId } }));
    return true;
}
export function mediaRoomUrl(url) {
    if (!url?.startsWith('/media/rooms/')) return url;
    const key = roomSession.ownerAccess ? 'managerToken' : 'roomToken';
    const value = roomSession.ownerAccess ? roomSession.managerToken : roomSession.roomToken;
    return `${url}${url.includes('?') ? '&' : '?'}${key}=${encodeURIComponent(value)}`;
}
