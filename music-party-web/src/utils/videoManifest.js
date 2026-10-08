const escapeXml = value => String(value ?? '').replace(/[<>&"']/g, char => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' }[char]));
const safeUrl = value => { try { const url = new URL(value); return url.protocol === 'https:' ? value : null; } catch { return null; } };
const validRange = range => /^\d+-\d+$/.test(range || '') && Number(range.split('-')[1]) >= Number(range.split('-')[0]);
export const trackContentType = track => `${track.mimeType}; codecs="${track.codecs}"`;
export function compatibleTracks(tracks, mediaSource = globalThis.MediaSource) {
    if (!mediaSource?.isTypeSupported) return [];
    return (tracks || []).filter(track => safeUrl(track.url) && track.bandwidth > 0 && validRange(track.initialization)
        && validRange(track.indexRange) && mediaSource.isTypeSupported(trackContentType(track)));
}

export function chooseDashTracks(source, preference, mediaSource = globalThis.MediaSource) {
    const videoTracks = compatibleTracks(source?.dash?.videoTracks, mediaSource);
    const audioTracks = compatibleTracks(source?.dash?.audioTracks, mediaSource)
        .sort((a, b) => b.bandwidth - a.bandwidth);
    // Prefer widely supported AVC when one quality has several codecs.
    const videos = [...videoTracks].sort((a, b) => a.quality - b.quality || a.bandwidth - b.bandwidth);
    const manual = preference?.mode === 'manual';
    let selected = videos;
    if (manual && videos.length) {
        const desired = videos.filter(track => track.qualityId === preference.qualityId);
        const target = Number(preference.qualityId?.slice(1));
        const fallback = videos.filter(track => track.quality <= target);
        const quality = desired[0]?.quality ?? fallback.at(-1)?.quality ?? videos[0].quality;
        selected = videos.filter(track => track.quality === quality);
    }
    // A manifest uses one codec family for safe seamless ABR and one chosen audio track.
    const preferredCodec = selected.some(track => /^avc1/i.test(track.codecs)) ? 'avc1' : selected[0]?.codecs?.split('.')[0];
    selected = selected.filter(track => track.codecs?.split('.')[0] === preferredCodec);
    const audio = audioTracks.find(track => /^mp4a/i.test(track.codecs)) || audioTracks[0];
    const audioCandidates = audioTracks.filter(track => /^mp4a/i.test(track.codecs));
    return { videoTracks: selected, videoCandidates: videos.filter(track => track.codecs?.split('.')[0] === preferredCodec),
        audioTracks: audio ? [audio] : [], audioCandidates: audioCandidates.length ? audioCandidates : audioTracks, available: videoTracks };
}

export function createDashManifest(dash, videoTracks, audioTracks) {
    if (!(dash?.durationMs > 0) || !videoTracks.length || !audioTracks.length) throw new Error('DASH requires compatible audio and video tracks');
    const representation = (track, type, index) => {
        // Preserve the server's primary-first order; an alternate standard port does not prove direct access.
        const urls = [...new Set([track.url, ...(track.backupUrls || [])].filter(safeUrl))];
        return `<Representation id="${type}-${track.id}-${index}" bandwidth="${Math.round(track.bandwidth)}" codecs="${escapeXml(track.codecs)}"${type === 'video' ? ` width="${track.width}" height="${track.height}"${track.frameRate ? ` frameRate="${escapeXml(track.frameRate)}"` : ''}` : ''}>${urls.map((url, i) => `<BaseURL serviceLocation="${type}-${index}-${i}">${escapeXml(url)}</BaseURL>`).join('')}<SegmentBase indexRange="${escapeXml(track.indexRange)}" indexRangeExact="true"><Initialization range="${escapeXml(track.initialization)}" /></SegmentBase></Representation>`;
    };
    const adaptation = (tracks, type) => `<AdaptationSet id="${type === 'video' ? 1 : 2}" contentType="${type}" mimeType="${escapeXml(tracks[0].mimeType)}" segmentAlignment="true" startWithSAP="1">${tracks.map((track, i) => representation(track, type, i)).join('')}</AdaptationSet>`;
    return `<?xml version="1.0" encoding="UTF-8"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" minBufferTime="PT${Math.max(1, Number(dash.minBufferTime) || 1.5)}S" mediaPresentationDuration="PT${dash.durationMs / 1000}S"><Period id="0" start="PT0S" duration="PT${dash.durationMs / 1000}S">${adaptation(videoTracks, 'video')}${adaptation(audioTracks, 'audio')}</Period></MPD>`;
}

export function playableAt(video, target, playbackRate = 1, dashPlayer = null) {
    if (!video || video.seeking || video.error || video.readyState < 3 || Math.abs(video.currentTime - target) > .35) return false;
    const duration = Number.isFinite(video.duration) ? video.duration : Infinity;
    const water = Math.min(Math.max(0, duration - target - .01), 1.5 * playbackRate);
    let combined = false;
    for (let i = 0; i < (video.buffered?.length || 0); i++) {
        if (video.buffered.start(i) <= target + .05 && video.buffered.end(i) >= target + water) combined = true;
    }
    if (!combined) return false;
    if (dashPlayer) {
        try {
            const metrics = dashPlayer.getDashMetrics();
            return ['audio', 'video'].every(type => Number(metrics.getCurrentBufferLevel(type)) >= Math.max(0, water - .1));
        } catch { return false; }
    }
    return true;
}
