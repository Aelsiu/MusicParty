const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const httpOrigin=origin=>{try{const url=new URL(origin);return ['http:','https:'].includes(url.protocol)&&url.origin===origin&&!url.username&&!url.password;}catch{return false;}};

export function embeddedPlayerIdentity(crypto=globalThis.crypto){
  if(crypto.randomUUID)return crypto.randomUUID();
  // getRandomValues also works on supported HTTP pages without randomUUID.
  const bytes=crypto.getRandomValues(new Uint8Array(16));bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;
  const hex=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}

export function isEmbeddedPlayerEligible({origin,userAgent='',mobile=false}={}) {
  if(mobile||!httpOrigin(origin)||/Android|iPhone|iPad|iPod|Mobile/i.test(userAgent))return false;
  return /(?:Chrome|Edg)\/\d+/.test(userAgent)&&!/OPR\/|SamsungBrowser\/|Electron\//.test(userAgent);
}

// The worker decides which exact origin is configured. Each document client
// separately binds replies to its pending connection and authorized revision.
export function createEmbeddedPlayerClient({window:win=window,origin=win.location.origin,onState=()=>{},handshakeTimeoutMs=5000}={}) {
  let alive=true,ready=false,seq=0,context='',connectionId='',revision=null,handshakeTimer;
  let pausedFrame=false,frameOwnerId='';
  let lastLiveIdentity=null;
  const clearPresentation=()=>{if(alive&&frameOwnerId)win.postMessage({channel:'MIRTH_EMBEDDED_PLAYER_COMMAND',type:'CLEAR_PRESENTATION',origin,frameOwnerId},origin);};
  const presentation=()=>pausedFrame&&frameOwnerId?{frameOwnerId}:{};
  const send=payload=>{if(alive&&connectionId)win.postMessage({channel:'MIRTH_EMBEDDED_PLAYER_COMMAND',...payload,connectionId,revision},origin);};
  const reset=()=>{clearTimeout(handshakeTimer);handshakeTimer=null;ready=false;context='';connectionId='';revision=null;};
  const invalidate=m=>{if(m.type==='REVOKED')lastLiveIdentity=null;reset();onState(m);};
  function pageHide(){
    clearPresentation();
    if(!alive||!connectionId)return;
    const unavailable={type:'UNAVAILABLE',origin,connectionId,revision};
    send({type:'DISCONNECT'});invalidate(unavailable);
  }
  function receive(event){
    if(!alive||event.source!==win||event.origin!==origin||event.data?.channel!=='MIRTH_EMBEDDED_PLAYER')return;
    const m=event.data;
    if(!connectionId){
      if(pausedFrame&&m.type==='REVOKED'&&uuid.test(m.frameOwnerId)&&m.frameOwnerId===frameOwnerId&&m.origin===origin
        &&m.connectionId===lastLiveIdentity?.connectionId&&m.revision===lastLiveIdentity?.revision){lastLiveIdentity=null;onState(m);}
      return;
    }
    if(m.connectionId!==connectionId||m.origin!==origin)return;
    if(m.type==='READY'){
      if(ready||m.protocol!==1||!uuid.test(m.revision))return;
      revision=m.revision;ready=true;pausedFrame=m.features?.pausedFrame===1;lastLiveIdentity={connectionId,revision};onState(m);
      clearTimeout(handshakeTimer);handshakeTimer=null;
    }else if(['REVOKED','UNAVAILABLE'].includes(m.type)){
      if(revision!==null&&m.revision!==revision)return;
      invalidate(m);
    }else if(m.type==='STATUS'&&ready&&m.revision===revision&&m.context===context)onState(m);
  }
  win.addEventListener('message',receive);
  win.addEventListener('pagehide',pageHide);
  return {
    connect(){
      if(!alive||connectionId)return;
      if(!httpOrigin(origin)){onState({type:'UNSUPPORTED_ORIGIN'});return;}
      connectionId=embeddedPlayerIdentity(win.crypto||globalThis.crypto);revision=null;seq=0;send({type:'CONNECT',...presentation()});
      const pending=connectionId;
      handshakeTimer=setTimeout(()=>{if(!alive||ready||connectionId!==pending)return;const message={type:'UNAVAILABLE',fault:'EXTENSION_UNAVAILABLE',reason:'HANDSHAKE_TIMEOUT',origin,connectionId,revision};send(pausedFrame&&frameOwnerId?{type:'RESET_CONNECTION',frameOwnerId}:{type:'DISCONNECT'});invalidate(message);},handshakeTimeoutMs);
    },
    setPresentationOwner(value){if(typeof value!=='string'||value!==''&&!uuid.test(value))throw new TypeError('Invalid presentation owner');if(value===frameOwnerId)return;clearPresentation();frameOwnerId=value;},
    reset(){send(pausedFrame&&frameOwnerId?{type:'RESET_CONNECTION',frameOwnerId}:{type:'DISCONNECT'});reset();},
    load(source,nextContext){if(!ready||typeof nextContext!=='string'||!nextContext)return false;context=nextContext;send({type:'LOAD',source,context,seq:seq++,...presentation()});return true;},
    sync(state){if(!ready||!context)return false;const {retainPausedFrame,...legacy}=state;send({type:'SYNC',...legacy,...presentation(),...(pausedFrame&&frameOwnerId?{retainPausedFrame:retainPausedFrame===true}:{}),context,seq:seq++});return true;},
    resetSource(){if(ready&&context){if(pausedFrame&&frameOwnerId)send({type:'RESET_SOURCE',context,seq:seq++,...presentation()});else send({type:'STOP',context,seq:seq++});}context='';},
    stop(){clearPresentation();if(ready&&context)send({type:'STOP',context,seq:seq++});context='';},
    dispose(){clearPresentation();send({type:'DISCONNECT'});alive=false;reset();win.removeEventListener('message',receive);win.removeEventListener('pagehide',pageHide);},
    get ready(){return ready;},
  };
}
