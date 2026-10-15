let current = null;
let publicationSequence = 0;
export const nextMediaPublication = () => ++publicationSequence;
const actions = ['play', 'pause', 'nexttrack', 'previoustrack', 'seekto', 'seekforward', 'seekbackward'];
export function claimMediaOwner(revoke) {
    current?.revoke();
    const owner = { revoke };
    current = owner;
    return {
        current: () => current === owner,
        release() { if (current !== owner) return false; revoke(); current = null; return true; }
    };
}
export function clearMediaActions() {
    const session = globalThis.navigator?.mediaSession;
    if (!session) return;
    for (const action of actions) { try { session.setActionHandler(action, null); } catch {} }
    session.metadata = null; session.playbackState = 'none';
    try { session.setPositionState?.(); } catch {}
}
