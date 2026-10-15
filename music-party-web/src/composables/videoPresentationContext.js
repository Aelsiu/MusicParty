import { computed, ref, shallowRef } from 'vue';

export const videoPresentationKey = Symbol('MusicParty video presentation');
export const presentationKey = videoPresentationKey;
export const VIDEO_PAGE_MODES = Object.freeze(['INLINE', 'WEB_FULLSCREEN', 'CONTAINER_FULLSCREEN']);

// Layout-scoped only: this owns DOM destinations, never playback or persistence.
export function createVideoPresentationContext(inlineDock = shallowRef(null)) {
    const registration = shallowRef(null), pageMode = ref('INLINE'), controlsVisible = ref(true);
    const dockMoving = ref(false);
    const locks = shallowRef(new Map());
    const menuClosers = new Map();
    const expanded = computed(() => pageMode.value !== 'INLINE');
    const roomDock = computed(() => expanded.value && registration.value ? registration.value.expandedDock : inlineDock.value);
    const interactionLocked = computed(() => locks.value.size > 0);
    const menuOpen = computed(() => [...locks.value.values()].some(lock => lock.kind === 'menu'));
    const isCurrent = token => !!registration.value && registration.value.ownerToken === token;
    const reset = () => { pageMode.value = 'INLINE'; controlsVisible.value = true; locks.value = new Map(); };
    const registerVideoSurface = ({ surface, expandedDock, ownerToken }) => {
        if (!surface || !expandedDock || ownerToken == null) throw new TypeError('Video surface, expanded dock and owner token are required');
        const entry = { surface, expandedDock, ownerToken };
        reset(); registration.value = entry;
        return () => { if (registration.value !== entry) return; reset(); registration.value = null; };
    };
    const setPageMode = (ownerToken, mode) => {
        if (!isCurrent(ownerToken) || !VIDEO_PAGE_MODES.includes(mode)) return false;
        pageMode.value = mode;
        return true;
    };
    const setControlsVisible = (ownerToken, visible) => {
        if (!isCurrent(ownerToken)) return false;
        controlsVisible.value = !!visible;
        return true;
    };
    const acquireInteractionLock = (kind = 'menu', ownerToken = registration.value?.ownerToken) => {
        if (!isCurrent(ownerToken)) return () => {};
        const id = Symbol(kind), next = new Map(locks.value); next.set(id, { kind, ownerToken }); locks.value = next;
        return () => { if (!locks.value.has(id)) return; const next = new Map(locks.value); next.delete(id); locks.value = next; };
    };
    const resetPresentation = ownerToken => { if (!isCurrent(ownerToken)) return false; reset(); return true; };
    const registerMenuCloser = close => { const id = Symbol(); menuClosers.set(id, close); return () => menuClosers.delete(id); };
    const closeMenu = () => { for (const close of menuClosers.values()) if (close()) return true; return false; };
    const dispose = () => { reset(); registration.value = null; menuClosers.clear(); };
    return { inlineDock, registration, pageMode, controlsVisible, expanded, roomDock, interactionLocked, menuOpen, dockMoving,
        isCurrent, registerVideoSurface, setPageMode, setControlsVisible, acquireInteractionLock,
        resetPresentation, registerMenuCloser, closeMenu, dispose };
}
