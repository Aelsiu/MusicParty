import client from './client';
import { PLAYBACK_PROTOCOL } from '../utils/playbackProtocol';
import { roomSession } from '../services/roomSession';
export const wireContext = ({ clientGeneration, ...context }) => context;
const options = () => ({ headers: { 'playback-protocol': String(PLAYBACK_PROTOCOL), 'X-Room-ID': roomSession.roomId, 'X-Room-Token': roomSession.roomToken } });
export const videoApi = {
    xifanSearch:(query,page,context,signal)=>client.post("/api/video/xifan/search",{query,page,...wireContext(context)},{...options(),signal,timeout:25000}),
    xifanPreview:(animeId,sourceId,context,signal)=>client.post("/api/video/xifan/preview",{animeId,...(sourceId==null?{}:{sourceId}),...wireContext(context)},{...options(),signal,timeout:25000}),
    resolve: (input, context, signal, provider) => client.post('/api/video/resolve', { input, ...(provider?{provider}:{}), ...wireContext(context) }, { ...options(), signal, timeout: 35000 }),
    danmakuSegment: (segment, context, playbackId, signal) => client.get(`/api/video/danmaku/segments/${segment}`, { ...options(), signal, params: { ...wireContext(context), playbackId } }),
    history: (context, page = 1, historyRevision) => client.get('/api/video/history', { ...options(), params: { ...wireContext(context), page, limit: 20, ...(historyRevision === undefined ? {} : { historyRevision }) } }),
    preview: (link, context, signal) => client.post('/api/video/favorites/preview', { link, ...wireContext(context) }, { ...options(), signal }),
    previewPage: (previewId, page, context) => client.get(`/api/video/favorites/previews/${encodeURIComponent(previewId)}`, { ...options(), params: { ...wireContext(context), page } })
};
export function matchesVideoRead(response, context, player, roomId) {
    return player.isContextCurrent(context) && response?.protocolVersion === PLAYBACK_PROTOCOL && response.roomId === roomId && response.requestId === context.requestId && response.playbackEpoch === context.expectedPlaybackEpoch && response.mediaMode === context.expectedMode && response.modeEpoch === context.expectedModeEpoch;
}
