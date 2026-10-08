export function createVideoWaitEventFilter(limit = 256) {
    const seen = new Set();
    return (event, player) => {
        if (event?.action !== 'VIDEO_WAIT_TIMEOUT' || !event.eventId || player.mediaMode !== 'VIDEO'
            || event.playbackId !== player.nowPlaying?.playbackId || !player.acceptsEvent(event) || seen.has(event.eventId)) return false;
        seen.add(event.eventId);
        while (seen.size > limit) seen.delete(seen.values().next().value);
        return true;
    };
}
