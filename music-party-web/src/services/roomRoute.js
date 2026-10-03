export function roomIdFromPath(path) {
    return /^\/([A-Za-z0-9]{8})\/?$/.exec(path)?.[1] || '';
}

// Read a room invitation once and erase its code before any asynchronous request.
// Only an actual room path may use pcd; license keys never have a query entry path.
export function consumeRoomPairingCode(id, browser = globalThis.window) {
    if (!browser || !id || roomIdFromPath(browser.location.pathname) !== id) return undefined;
    const params = new URLSearchParams(browser.location.search || '');
    if (!params.has('pcd')) return undefined;
    const values = params.getAll('pcd');
    browser.history.replaceState(browser.history.state ?? null, '', `/${id}`);
    // Ambiguous invitations ask for a manual code instead of selecting one value.
    return values.length === 1 ? values[0] : '';
}

export function navigateRoom(id = '', browser = globalThis.window) {
    if (!browser) return;
    const path = id ? `/${id}` : '/';
    if (browser.location.pathname === path && !browser.location.search && !browser.location.hash) return;
    if (browser.location.pathname === path || (id && roomIdFromPath(browser.location.pathname) === id)) browser.history.replaceState(null, '', path);
    else browser.history.pushState(null, '', path);
    browser.dispatchEvent(new Event('musicparty:route'));
}
