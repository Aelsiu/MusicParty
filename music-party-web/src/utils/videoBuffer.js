// Snapshot local media ranges in milliseconds; retain gaps instead of implying a full prefix.
export function readVideoBufferedRanges(media, durationMs) {
    if (!media || media.readyState < 1 || !Number.isFinite(durationMs) || durationMs <= 0) return [];
    const ranges = [];
    try {
        const buffered = media.buffered;
        for (let i = 0; i < (buffered?.length || 0); i++) {
            const start = Math.max(0, buffered.start(i) * 1000);
            const end = Math.min(durationMs, buffered.end(i) * 1000);
            if (Number.isFinite(start) && Number.isFinite(end) && end > start) ranges.push([start, end]);
        }
    } catch { return []; }
    ranges.sort((a, b) => a[0] - b[0]);
    return ranges.reduce((merged, range) => {
        const previous = merged.at(-1);
        if (previous && range[0] <= previous[1]) previous[1] = Math.max(previous[1], range[1]);
        else merged.push(range);
        return merged;
    }, []);
}
