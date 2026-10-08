import test, { beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { register } from 'node:module';
import { createPinia, setActivePinia } from 'pinia';

register(new URL('./helpers/source-resolution.js', import.meta.url));
const storage = () => ({ getItem: () => null, setItem() {}, removeItem() {} });
globalThis.localStorage = storage(); globalThis.sessionStorage = storage();
globalThis.window = { dispatchEvent() {}, location: { pathname: '/', origin: 'http://localhost', search: '', hash: '' }, history: { pushState() {}, replaceState() {} } };
const { usePlayerStore } = await import('../src/stores/player.js');
const { useUserStore } = await import('../src/stores/user.js');
const { socketService } = await import('../src/services/socket.js');
const { roomSession } = await import('../src/services/roomSession.js');
const { adminApi } = await import('../src/api/admin.js');
const { useChatStore } = await import('../src/stores/chat.js');
const { useToastStore } = await import('../src/stores/toast.js');
const { createSocketSubscriptions } = await import('../src/services/socketHandler.js');
const { WS_DEST } = await import('../src/constants/api.js');
const { default: client } = await import('../src/api/client.js');
let player, sent;
const snapshot = overrides => ({ protocolVersion: 3, playbackEpoch: 'runtime', mediaMode: 'MUSIC', modeEpoch: 0,
    stateRevision: 1, queueRevision: 1, serverTime: Date.now(), nowPlaying: null, queue: [], isPaused: true,
    ...overrides });
beforeEach(() => {
    setActivePinia(createPinia()); sent = []; socketService.send = (...args) => { sent.push(args); return true; };
    useUserStore().isGuest = false; roomSession.ownerAccess = true;
    player = usePlayerStore(); player.beginSynchronization(5); player.connected = true;
});
afterEach(() => player.resetPlayback());
test('all media controls remain disabled until a v3 full state arrives', () => {
    assert.equal(player.togglePause(), false); assert.equal(player.enqueue('netease', '1'), false);
    assert.equal(player.syncState({ queue: [], isPaused: true }, 5), false);
    assert.equal(player.synchronized, false);
    assert.equal(player.syncState(snapshot(), 5), true);
    assert.equal(player.enqueue('netease', '1'), true);
    assert.equal(sent.at(-1)[1].expectedPlaybackEpoch, 'runtime');
    assert.equal(sent.at(-1)[1].expectedMode, 'MUSIC');
    assert.match(sent.at(-1)[1].requestId, /.+/);
});
test('mode changes preserve snapshot authority, reject stale confirmation, and filter old queues', () => {
    player.syncState(snapshot(), 5); const context = player.playbackContext();
    assert.equal(player.switchMediaMode('VIDEO', context), true);
    assert.equal(player.mediaMode, 'MUSIC');
    player.syncQueue(snapshot({ queueRevision: 4, queue: [{ music: { id: 'newer' } }] }), 5);
    player.syncState(snapshot({ stateRevision: 2, queueRevision: 2, queue: [] }), 5);
    assert.equal(player.queue[0].music.id, 'newer');
    player.syncState(snapshot({ mediaMode: 'VIDEO', modeEpoch: 1, stateRevision: 3, queueRevision: 5 }), 5);
    assert.equal(player.switchMediaMode('MUSIC', context), false);
    assert.equal(player.syncQueue(snapshot({ queueRevision: 6 }), 5), false);
    assert.equal(player.acceptsEvent({ playbackEpoch: 'runtime', modeEpoch: 0, mediaMode: 'MUSIC' }), false);
    assert.deepEqual(player.queue, []);
});
test('video refresh includes source and instance context and video does not send likes', () => {
    player.syncState(snapshot({ mediaMode: 'VIDEO', nowPlaying: { playbackId: 'video-a', sourceRevision: 8,
        currentPosition: 4000, serverTime: Date.now(), music: { platform: 'bilibili-video', id: 'BVabc:p1', duration: 90000 } } }), 5);
    player.refreshVideoSource('MEDIA_NETWORK_ERROR');
    assert.deepEqual(sent.at(-1)[1], { requestId: sent.at(-1)[1].requestId, expectedPlaybackEpoch: 'runtime',
        expectedMode: 'VIDEO', expectedModeEpoch: 0, playbackId: 'video-a', sourceRevision: 8, reason: 'MEDIA_NETWORK_ERROR' });
    const count = sent.length; player.sendLike(); assert.equal(sent.length, count);
    assert.equal(player.lyricText, ''); assert.deepEqual(player.chorusMarkers, []);
});
test('reconnect with identical runtime rejects old callbacks and old command context', () => {
    player.syncState(snapshot(), 5); const context = player.playbackContext();
    player.beginSynchronization(6);
    assert.equal(player.sendMedia('/app/control/next', {}, context), false);
    assert.equal(player.syncState(snapshot({ stateRevision: 10 }), 5), false);
    assert.equal(player.syncState(snapshot({ stateRevision: 11 }), 6), true);
    assert.equal(player.sendMedia('/app/control/next', {}, context), false);
});
test('management HTTP uses protocol 3 and current instance even for empty playback', async () => {
    player.syncState(snapshot(), 5); let request;
    client.defaults.adapter = async config => { request = config; return { data: { message: 'ok' }, status: 200, headers: {}, config }; };
    await adminApi.playerAction('', 'PAUSE');
    assert.equal(request.headers.get('playback-protocol'), '3');
    const body = JSON.parse(request.data);
    assert.equal(body.playbackId, null); assert.equal(body.expectedMode, 'MUSIC');
    assert.equal(body.expectedModeEpoch, 0);
});
test('guest automatic video recovery stays silent while manual refresh requests a name', () => {
    player.syncState(snapshot({ mediaMode: 'VIDEO', nowPlaying: { playbackId: 'video-a', sourceRevision: 1,
        currentPosition: 0, music: { platform: 'bilibili-video', id: 'BVabc:p1', duration: 90000 } } }), 5);
    const user = useUserStore(); user.isGuest = true; user.showNameModal = false;
    assert.equal(player.refreshVideoSource('MEDIA_NETWORK_ERROR'), false);
    assert.equal(player.refreshVideoSource('BUFFER_TIMEOUT'), false);
    assert.equal(user.showNameModal, false); assert.equal(sent.length, 0);
    assert.equal(player.refreshVideoSource('USER_REQUEST'), false);
    assert.equal(user.showNameModal, true); assert.equal(sent.length, 0);
});
test('playback reset event retains shared room chat', () => {
    player.syncState(snapshot(), 5);
    const chat = useChatStore(); chat.messages = [{ id: 'retained', content: '房间聊天' }];
    useToastStore().add = () => {};
    createSocketSubscriptions()[WS_DEST.TOPIC_EVENTS]({ action: 'RESET', type: 'WARN', userId: 'SYSTEM',
        message: '当前播放模式已重置', playbackEpoch: 'runtime', mediaMode: 'MUSIC', modeEpoch: 0 });
    assert.equal(chat.messages.length, 1); assert.equal(chat.messages[0].id, 'retained');
});
test('video snapshots compensate latency and advance at room rate while waiting stays frozen', () => {
    const current = performance.now(), wall = Date.now();
    player.receiveClock({});
    player.syncState(snapshot({ mediaMode: 'VIDEO', playbackRate: 2, playIntent: 'PLAYING', isPaused: false,
        nowPlaying: { playbackId: 'v', sourceRevision: 1, currentPosition: 1000, music: { duration: 90000 } } }), 5);
    assert.equal(player.playbackRate, 2); assert.equal(player.intentPaused, false);
    assert.ok(player.getCurrentProgress() >= 1000);
    player.syncState(snapshot({ stateRevision: 2, mediaMode: 'VIDEO', playbackRate: 2, playIntent: 'PLAYING', isPaused: true,
        videoWait: { waitId: 'w', waitRevision: 1, frozenPosition: 3000 },
        nowPlaying: { playbackId: 'v', sourceRevision: 1, currentPosition: 3000, music: { duration: 90000 } } }), 5);
    assert.equal(player.getCurrentProgress(), 3000); assert.equal(player.intentPaused, false);
    player.togglePause(); assert.equal(sent.at(-1)[0], WS_DEST.PLAYER_PAUSE);
    player.syncState(snapshot({ stateRevision: 3, mediaMode: 'VIDEO', playbackRate: 1.5, playIntent: 'PAUSED', isPaused: true,
        nowPlaying: { playbackId: 'v', sourceRevision: 1, currentPosition: 3000, music: { duration: 90000 } } }), 5);
    assert.equal(player.intentPaused, true); assert.equal(player.videoWait, null);
});
test('guest observation is independent of naming and video controls retain owner checks', () => {
    player.syncState(snapshot({ mediaMode: 'VIDEO', nowPlaying: { playbackId: 'v', sourceRevision: 9, currentPosition: 0, music: { duration: 90000 } } }), 5);
    const user = useUserStore(); user.isGuest = true; user.showNameModal = false;
    assert.equal(player.reportVideoWatch({ status: 'BLOCKED', sourceGeneration: 3, sequence: 1, foreground: true, playbackAllowed: false }), true);
    assert.equal(sent.at(-1)[0], WS_DEST.VIDEO_WATCH_STATUS); assert.equal(sent.at(-1)[1].sourceRevision, 9);
    assert.equal(user.showNameModal, false);
    roomSession.ownerAccess = false; user.isGuest = false;
    assert.equal(player.setVideoRate(1.5), false); assert.equal(player.switchVideoPage(2), false);
    roomSession.ownerAccess = true;
    assert.equal(player.setVideoRate(1.5), true); assert.equal(sent.at(-1)[1].playbackRate, 1.5);
    assert.equal(player.switchVideoPage(2), true); assert.equal(sent.at(-1)[1].page, 2);
    player.isSkipLocked = true; assert.equal(player.switchVideoPage(3), false);
});
test('timeout toast uses event identity once across subscriptions and retains the full display name', () => {
    player.syncState(snapshot({ mediaMode: 'VIDEO', nowPlaying: { playbackId: 'v', sourceRevision: 9, currentPosition: 0, music: { duration: 90000 } } }), 5);
    const events = [], store = useToastStore(); store.add = value => events.push(value);
    const subscriptions = createSocketSubscriptions();
    const event = { action: 'VIDEO_WAIT_TIMEOUT', type: 'WARN', eventId: 'timeout-v-specific', waitId: 'w', playbackId: 'v',
        userName: '很长但需要完整显示的观看成员名字', playbackEpoch: 'runtime', mediaMode: 'VIDEO', modeEpoch: 0 };
    subscriptions[WS_DEST.TOPIC_EVENTS](event); subscriptions[WS_DEST.USER_EVENTS](event);
    assert.equal(events.length, 1); assert.equal(events[0].title, '等待缓冲超时');
    assert.match(events[0].message, /很长但需要完整显示的观看成员名字/); assert.equal(events[0].multiline, true);
    subscriptions[WS_DEST.TOPIC_EVENTS]({ ...event, eventId: 'old-instance', playbackId: 'old' });
    assert.equal(events.length, 1);
});

test('P2 capability defaults, zero vote wait and private interaction revision barrier', () => {
    roomSession.roomId = 'p2-room';
    player.syncState(snapshot({mediaMode:'VIDEO',modeEpoch:1,voteSkipWaitTime:0,nowPlaying:{playbackId:'p',music:{duration:60000},currentPosition:0},videoFeatures:{interactionState:true}}),5);
    assert.equal(player.voteSkipWaitTime,0);
    const message={protocolVersion:3,roomId:'p2-room',playbackEpoch:'runtime',mediaMode:'VIDEO',modeEpoch:1,playbackId:'p',stateRevision:2,interactionRevision:1,canVote:true,hasVoted:true,reason:'ELIGIBLE'};
    assert.equal(player.receiveInteraction(message),false); assert.equal(player.interaction,null);
    player.syncState(snapshot({mediaMode:'VIDEO',modeEpoch:1,stateRevision:2,nowPlaying:{playbackId:'p',music:{duration:60000},currentPosition:0},videoFeatures:{interactionState:true}}),5);
    assert.equal(player.interaction.hasVoted,true);
    assert.equal(player.receiveInteraction({...message,interactionRevision:0,hasVoted:false}),false);
    assert.equal(player.receiveInteraction({...message,playbackId:'old',interactionRevision:2}),false);
});
test('P2 imports cannot regress terminal status or establish unknown epoch and recovery handles pending', () => {
    roomSession.roomId='p2-room';
    player.syncState(snapshot({mediaMode:'VIDEO',modeEpoch:1,policyRecoveryRequired:true,videoPending:{queueId:'q'}}),5);
    assert.equal(player.recoverVideoSource(),true); assert.equal(sent.at(-1)[1].playbackId,null); assert.equal(sent.at(-1)[1].queueId,'q');
    const result={kind:'IMPORT_RESULT',protocolVersion:3,roomId:'p2-room',playbackEpoch:'runtime',mediaMode:'VIDEO',modeEpoch:1,requestId:'batch',operationRevision:3,status:'PARTIAL',items:[]};
    assert.equal(player.receiveVideoImport(result),true);
    assert.equal(player.receiveVideoImport({...result,kind:'IMPORT_ACCEPTED',operationRevision:1,status:'ACCEPTED'}),false);
    assert.equal(player.videoImports.batch.status,'PARTIAL');
    assert.equal(player.receiveVideoImport({...result,playbackEpoch:'stranger',operationRevision:4}),false);
});

test('import status query always carries original request identity with operation identity',()=>{
 player.syncState(snapshot(),5);player.queryVideoImport({requestId:'original',operationId:'op'});
 assert.equal(sent.at(-1)[1].originalRequestId,'original');assert.equal(sent.at(-1)[1].operationId,'op');
});
