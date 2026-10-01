const empty = () => ({ open: false, code: '', nextUpdateAt: 0, intervalMinutes: 10, offset: 0 });

// The public component never uses the manager endpoint. Closed state drops the code,
// and invalidates in-flight responses so an earlier OPEN request cannot restore it.
export function createPublicPairingSession({ fetchPairing, publish, now = Date.now }) {
    let active = false, roomId = '', generation = 0, knownClosed = false, state = empty();
    function clear() { state = empty(); publish(state); }
    async function refresh() {
        if (!active || !roomId) return;
        const current = ++generation, id = roomId;
        try {
            const result = await fetchPairing(id);
            if (!active || current !== generation || id !== roomId) return;
            if (result.open !== true) { knownClosed = true; clear(); return; }
            if (typeof result.pairingCode !== 'string' || !/^\d{4}$/.test(result.pairingCode) || !Number.isFinite(result.nextUpdateAt)
                || !Number.isFinite(result.serverTime) || result.nextUpdateAt <= result.serverTime
                || !Number.isInteger(result.pairingIntervalMinutes)
                || result.pairingIntervalMinutes < 1 || result.pairingIntervalMinutes > 60) throw new Error('Invalid pairing response');
            knownClosed = false;
            state = { open: true, code: result.pairingCode, nextUpdateAt: result.nextUpdateAt,
                intervalMinutes: result.pairingIntervalMinutes, offset: result.serverTime - now() };
            publish(state);
        } catch {
            if (active && current === generation && id === roomId) { knownClosed = false; clear(); }
        }
    }
    return {
        start(id) { ++generation; active = true; roomId = id; knownClosed = false; clear(); return refresh(); },
        stop() { ++generation; active = false; roomId = ''; knownClosed = false; clear(); },
        refresh,
        changed(detail = {}) {
            if (!active) return;
            if (detail?.open === false) { ++generation; knownClosed = true; clear(); return; }
            if (detail?.open === true || !knownClosed) return refresh();
        },
        tick() {
            if (active && state.open && state.nextUpdateAt <= now() + state.offset) {
                ++generation; knownClosed = false; clear(); return refresh();
            }
        }
    };
}
