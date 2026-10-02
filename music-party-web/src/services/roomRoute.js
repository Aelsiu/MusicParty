export function roomIdFromPath(path) {
    return /^\/([A-Za-z0-9]{8})\/?$/.exec(path)?.[1] || '';
}

export function navigateRoom(id = '', browser = globalThis.window) {
    if (!browser) return;
    const path = id ? `/${id}` : '/';
    if (browser.location.pathname === path) return;
    if (id && roomIdFromPath(browser.location.pathname) === id) browser.history.replaceState(null, '', path);
    else browser.history.pushState(null, '', path);
    browser.dispatchEvent(new Event('musicparty:route'));
}
