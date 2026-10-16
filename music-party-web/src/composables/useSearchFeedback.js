import { ref, computed, watch, getCurrentScope, onScopeDispose } from 'vue';

export const searchFeedbackKey = Symbol('search feedback');
const names = { search: ['Searching...', 'Search'], load: ['Loading...', 'Load'], queue: ['Queueing...', 'Queue'] };
let eventHandler;
export function setSearchEventHandler(handler) { eventHandler=handler; return ()=>{if(eventHandler===handler)eventHandler=null;}; }
export function routeSearchEvent(event, userToken) { return eventHandler?.(event,userToken)===true; }

/** A send acknowledgement is not an enqueue result. Observe a new queue row or playback instance. */
export function observeEnqueue(player, id, context, timeoutMs = 10000, platform = null) {
    const target=String(id),matches=music=>String(music?.id)===target && (!platform || music?.platform===platform);
    const existing=new Set(player.queue.map(row=>row.queueId).filter(Boolean));
    const beforeCount=player.queue.filter(row=>matches(row.music)).length;
    const beforePlayback=player.nowPlaying?.playbackId,beforeId=String(player.nowPlaying?.music?.id||'');
    const beforePlatform=player.nowPlaying?.music?.platform;
    let stop,timer,resolve,settled=false;
    const promise=new Promise(complete=>resolve=complete);
    const finish=value=>{if(settled)return;settled=true;clearTimeout(timer);stop?.();resolve(value);};
    const check=()=>{
        if(!player.isContextCurrent(context)){finish(false);return;}
        const added=player.queue.some(row=>matches(row.music)&&row.queueId&&!existing.has(row.queueId))
            ||player.queue.filter(row=>matches(row.music)).length>beforeCount;
        const started=matches(player.nowPlaying?.music)&&(beforeId!==target||platform&&beforePlatform!==platform||player.nowPlaying?.playbackId!==beforePlayback);
        if(added||started)finish(true);
    };
    stop=watch(()=>[player.queue,player.nowPlaying,player.clientGeneration,player.modeEpoch,player.synchronized],check,{flush:'sync'});
    timer=setTimeout(()=>finish(false),timeoutMs);
    if(getCurrentScope())onScopeDispose(()=>finish(false));
    return {promise,cancel:()=>finish(false),check};
}

/** One action owns the feedback slot until replaced or cleared. Late replies cannot replace it. */
export function useSearchFeedback() {
    const state = ref(null), detailsOpen = ref(false);
    let generation = 0;
    const clear = () => { generation++; state.value = null; detailsOpen.value = false; };
    const begin = kind => {
        clear(); const ticket = generation;
        state.value = { ticket, kind, pending: true, text: names[kind][0], details: '' };
        return ticket;
    };
    const finish = (ticket, success, details = '') => {
        if (ticket !== generation || !state.value) return false;
        state.value = { ticket, kind: state.value.kind, pending: false, success,
            text: `${names[state.value.kind][1]} ${success ? 'Success' : 'Failed'}`, details };
        return true;
    };
    const fail = (kind, message) => { const ticket = begin(kind); finish(ticket, false, message); };
    onScopeDispose(clear);
    return { state, detailsOpen, pending: computed(() => state.value?.pending === true), begin, finish, fail, clear };
}
