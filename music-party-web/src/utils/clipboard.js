// Invoke this during the send gesture: ClipboardItem can wait for fresh API data
// without losing the user activation required by some browsers.
export async function copyAsyncText(text, {
    clipboard = globalThis.navigator?.clipboard,
    ClipboardItem = globalThis.ClipboardItem,
    document = globalThis.document
} = {}) {
    const value = Promise.resolve(text);
    value.catch(() => {});
    if (clipboard?.write && ClipboardItem) {
        const data = value.then(text => new Blob([text], { type: 'text/plain' }));
        data.catch(() => {});
        try {
            await clipboard.write([new ClipboardItem({ 'text/plain': data })]);
            return;
        } catch {
            // Older implementations may reject promise-backed ClipboardItems.
        }
    }
    const resolved = await value;
    if (clipboard?.writeText) {
        await clipboard.writeText(resolved);
        return;
    }
    if (!document?.execCommand) throw new Error('Clipboard unavailable');

    // Compatibility for HTTP deployments without the async Clipboard API.
    const focused = document.activeElement;
    const selection = focused?.selectionStart == null ? null
        : [focused.selectionStart, focused.selectionEnd];
    const input = document.createElement('textarea');
    input.value = resolved;
    input.readOnly = true;
    input.style.cssText = 'position:fixed;top:0;left:-9999px;opacity:0';
    document.body.appendChild(input);
    try {
        input.select();
        if (!document.execCommand('copy')) throw new Error('Copy rejected');
    } finally {
        input.remove();
        focused?.focus({ preventScroll: true });
        if (selection) focused.setSelectionRange(...selection);
    }
}
