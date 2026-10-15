import {createEmbeddedPlayerClient,embeddedPlayerIdentity} from './embeddedPlayerClient.js';
import {chooseDashTracks,qualityOptionsForTracks} from './videoManifest.js';

const fields=['id','url','backupUrls','bandwidth','codecs','mimeType','initialization','indexRange','width','height','frameRate','quality','qualityId','qualityLabel'];
const track=t=>Object.fromEntries(fields.filter(k=>t[k]!==undefined).map(k=>[k,k==='backupUrls'?Array.from(t[k]||[]):t[k]]));
const ranges=values=>({length:values.length,start:i=>values[i][0],end:i=>values[i][1]});
const abort=()=>new DOMException('Playback context changed','AbortError');

// Bounded media state/control adapter; receives no media bytes and never fetches CDN.
export function createEmbeddedMedia({window:win=window,onEvent=()=>{},onConnection=()=>{},clientFactory=createEmbeddedPlayerClient,now=()=>performance.now()}={}) {
  const instanceId=embeddedPlayerIdentity(win.crypto||globalThis.crypto);
  let alive=true,generation=0,sourceSerial=0,context='',position=0,paused=true,rate=1,volume=.25,src='',playRequest=null;
  let frameOwnerId='',retainPausedFrame=false;
  // Renderer timeupdate crosses the worker/bridge only a few times per second.
  // Keep that authoritative sample for synchronization; interpolate only the
  // foreground presentation clock, and stop predicting after a missed report.
  let presentationPosition=0,presentationAt=now(),presentationRunning=false,stalled=false;
  const presentationTime=()=>{
    let value=presentationPosition+(presentationRunning?Math.min(500,Math.max(0,now()-presentationAt))/1000*rate:0);
    if(Number.isFinite(media.duration)&&media.duration>0)value=Math.min(value,media.duration);
    for(let i=0;i<media.buffered.length;i++)if(media.buffered.start(i)<=presentationPosition&&media.buffered.end(i)>=presentationPosition){value=Math.min(value,media.buffered.end(i));break;}
    return value;
  };
  const anchorPresentation=(value,running=false)=>{presentationPosition=value;presentationAt=now();presentationRunning=running;};
  const listeners=new Map(),readyWaiters=new Set();
  const emit=type=>{for(const fn of listeners.get(type)||[])fn({type,target:media});onEvent(type);};
  const rejectPlay=error=>{if(playRequest){clearTimeout(playRequest.timer);playRequest.reject(error);playRequest=null;}};
  const rejectReady=error=>{for(const waiter of [...readyWaiters])waiter.reject(error);};
  const send=()=>client.sync({position,paused:desiredPaused,rate,volume,retainPausedFrame:retainPausedFrame&&desiredPaused});let desiredPaused=true;
  const client=clientFactory({window:win,onState:m=>{
    if(!alive)return;
    if(m.type==='READY'){media.connectionState='READY';for(const waiter of [...readyWaiters])waiter.resolve();onConnection(m.type);return;}
    if(['REVOKED','UNAVAILABLE','UNSUPPORTED_ORIGIN'].includes(m.type)){
      anchorPresentation(presentationTime());generation++;context='';desiredPaused=true;
      const error=new DOMException('Extension unavailable','NotSupportedError');rejectReady(error);rejectPlay(error);
      const fault=m.fault||(m.type==='REVOKED'?'EXTENSION_REVOKED':m.type==='UNSUPPORTED_ORIGIN'?'EXTENSION_UNAVAILABLE':'EXTENSION_CONNECTION_LOST');
      media.connectionState=fault==='EXTENSION_CONNECTION_LOST'?'SUSPENDED':'BLOCKED';
      media.readyState=0;media.error={code:0,fault};media.seeking=false;media.buffered=ranges([]);media.seekable=ranges([]);paused=true;emit('error');onConnection(m.type);return;
    }
    if(m.type!=='STATUS'||m.context!==context)return;
    const drawnPosition=presentationTime(),previousPosition=position;
    position=m.position;paused=m.paused;media.duration=m.duration;media.readyState=m.readyState;media.seeking=m.seeking;media.ended=m.ended;
    media.buffered=ranges(m.buffered||[]);media.seekable=ranges(m.seekable||[]);
    if(m.event==='waiting')stalled=true;
    else if(['playing','canplay','seeked'].includes(m.event)||position>previousPosition)stalled=false;
    const running=!paused&&media.readyState>=3&&!media.seeking&&!media.ended&&!m.error&&!stalled;
    // Small IPC jitter must not rewind the timeline and delete text in flight.
    // Real seeks, source changes and large corrections retain their exact clock.
    const continuous=!media.seeking&&!media.ended&&Math.abs(position-drawnPosition)<.25;
    anchorPresentation(continuous?Math.max(position,drawnPosition):position,running);
    if(m.error==='AUTOPLAY_BLOCKED')rejectPlay(new DOMException('Click the embedded player to play','NotAllowedError'));
    if(m.error==='LOAD_FAILED'){media.error={code:4,fault:'MEDIA_LOAD_FAILED'};rejectPlay(new DOMException('Media unavailable','NotSupportedError'));emit('error');return;}
    if(playRequest&&!paused&&media.readyState>=3){clearTimeout(playRequest.timer);playRequest.resolve();playRequest=null;}
    emit(m.event||'timeupdate');
  }});
  const media={
    embedded:true,connectionState:'CONNECTING',readyState:0,duration:NaN,seeking:false,ended:false,error:null,buffered:ranges([]),seekable:ranges([]),muted:false,
    setPresentationOwner(value){if(typeof value!=='string'||value!==''&&!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value))throw new TypeError('Invalid presentation owner');client.setPresentationOwner?.(value);frameOwnerId=value;},
    setRetainPausedFrame(value){retainPausedFrame=value===true;send();},
    get currentTime(){return position;},set currentTime(value){if(!Number.isFinite(value))return;position=Math.max(0,Math.min(86400,value));anchorPresentation(position);media.seeking=true;emit('seeking');send();},
    get presentationTime(){return presentationTime();},
    get paused(){return paused;},get playbackRate(){return rate;},set playbackRate(value){anchorPresentation(presentationTime(),presentationRunning);rate=value;send();},
    get volume(){return volume;},set volume(value){volume=value;send();},
    get src(){return src;},set src(value){src=value;},get currentSrc(){return src;},
    removeAttribute(name){if(name==='src')src='';},getAttribute(name){return name==='src'?src:null;},
    addEventListener(type,fn){if(!listeners.has(type))listeners.set(type,new Set());listeners.get(type).add(fn);},
    removeEventListener(type,fn){listeners.get(type)?.delete(fn);},
    connect(){if(client.ready){media.connectionState='READY';return;}media.connectionState='CONNECTING';client.connect();},
    resetConnection(){generation++;context='';rejectReady(abort());rejectPlay(abort());client.reset?.();media.connectionState='RECOVERING';},
    pause(){anchorPresentation(presentationTime());desiredPaused=true;rejectPlay(abort());send();},
    play(){
      if(!alive||!context)return Promise.reject(abort());
      if(!paused&&media.readyState>=3)return Promise.resolve();
      if(playRequest)return playRequest.promise;
      desiredPaused=false;let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});
      playRequest={promise,resolve,reject,timer:setTimeout(()=>rejectPlay(new DOMException('Media did not start','NotAllowedError')),6000)};send();return promise;
    },
    load(){generation++;client.stop();context='';rejectReady(abort());rejectPlay(abort());position=0;anchorPresentation(0);stalled=false;paused=true;desiredPaused=true;media.readyState=0;media.duration=NaN;media.ended=false;media.error=null;media.seeking=false;media.buffered=ranges([]);media.seekable=ranges([]);},
    resetSource({preservePresentationOwnerId}={}){if(!frameOwnerId||preservePresentationOwnerId!==frameOwnerId){media.load();return;}generation++;client.resetSource?.();context='';rejectReady(abort());rejectPlay(abort());anchorPresentation(presentationTime());stalled=false;paused=true;desiredPaused=true;media.readyState=0;media.duration=NaN;media.ended=false;media.error=null;media.seeking=false;media.buffered=ranges([]);media.seekable=ranges([]);},
    async loadEmbeddedSource(source,preference,key){
      const token=generation;const selected=chooseDashTracks(source,preference);
      const payload=source?.dash?{kind:'dash',durationMs:source.dash.durationMs,videoTracks:selected.videoTracks.map(track),audioTracks:selected.audioTracks.map(track)}
        :{kind:'mp4',url:source?.mp4?.url||source?.url};
      if(!alive)throw abort();
      if(!client.ready)await new Promise((resolve,reject)=>{
        const finish=fn=>value=>{clearTimeout(timer);readyWaiters.delete(waiter);fn(value);};
        const waiter={resolve:finish(resolve),reject:finish(reject)};
        const timer=setTimeout(()=>waiter.reject(new DOMException('Enable the extension and refresh','NotSupportedError')),5000);readyWaiters.add(waiter);
        media.connect();
      });
      if(!alive||token!==generation)throw abort();
      context=`${instanceId}:${generation}:${++sourceSerial}`;src='embedded';
      if(!client.load(payload,context))throw new DOMException('Extension unavailable','NotSupportedError');
      const rendered=selected.videoTracks[0];
      return {kind:payload.kind,options:qualityOptionsForTracks(selected.available),quality:rendered?.qualityLabel||(rendered?.height>0?`${Math.min(rendered.height,rendered.width||rendered.height)}P`:source?.mp4?.qualityLabel||payload.kind.toUpperCase())};
    },
    dispose(){anchorPresentation(presentationTime());alive=false;media.connectionState='DISPOSED';generation++;context='';rejectReady(abort());rejectPlay(abort());client.dispose();listeners.clear();},
  };
  return media;
}
