import {validateCommand} from './policy.js';
import {createDashManifest,chooseDashTracks} from './vendor/videoManifest.js';
import {parserOriginPattern,CDN_ORIGINS} from './parserConfig.js';
const video=document.querySelector('#media'),notice=document.querySelector('#notice');
const canvas=document.querySelector('#paused-frame');
const port=chrome.runtime.connect({name:'mirth-renderer'});
let authorized=false,context='',seq=-1,engine=null,blob=null,desired=null,loadGeneration=0,pendingSeek=false;
let proofPort=null,candidate=null,session=null,challenge='',attested=false,ruleIds=[],cleanup=null,revoking=false;
let phase='ACTIVE',frameOwnerId='',bitmapOwner='',observed=null,frameSerial=0,frameCallback=null,targetSeq=-1,handoff=false;
let destructionReason='';
const proof=m=>{try{proofPort?.postMessage({token:location.hash.slice(1),...session,...m});}catch{}};
function clearBitmap(){bitmapOwner='';handoff=false;if(canvas){canvas.hidden=true;canvas.width=0;canvas.height=0;}video.style&&(video.style.opacity='1');}
function validFrame(){return observed&&observed.generation===loadGeneration&&desired&&desired.frameOwnerId===frameOwnerId&&Math.abs(observed.mediaTime-desired.position)<=.5&&!video.seeking;}
function capture(){
  if(!authorized||bitmapOwner||!canvas||!desired?.paused||desired.retainPausedFrame!==true||!video.paused||!validFrame()||!video.videoWidth||!video.videoHeight)return;
  const scale=Math.min(1,1920/Math.max(video.videoWidth,video.videoHeight),Math.sqrt(2073600/(video.videoWidth*video.videoHeight)));
  try{canvas.width=Math.max(1,Math.floor(video.videoWidth*scale));canvas.height=Math.max(1,Math.floor(video.videoHeight*scale));canvas.getContext('2d').drawImage(video,0,0,canvas.width,canvas.height);
    bitmapOwner=frameOwnerId;phase='PAUSED_FRAME_READY';canvas.hidden=false;video.style.opacity='.001';proof({type:'FRAME_CAPTURED',frameOwnerId,context,frameSeq:observed.frameSeq,mediaTime:observed.mediaTime,width:canvas.width,height:canvas.height});
  }catch{clearBitmap();}
}
function presented(){
  if(!authorized||!frameOwnerId||!validFrame())return;
  proof({type:'FRAME_PRESENTED',frameOwnerId,context,targetSeq,frameSeq:observed.frameSeq,mediaTime:observed.mediaTime,width:video.videoWidth,height:video.videoHeight});capture();
}
function observe(){
  if(!authorized||frameCallback!==null||!video.requestVideoFrameCallback)return;
  const generation=loadGeneration;
  frameCallback=video.requestVideoFrameCallback((_,metadata)=>{if(!authorized||generation!==loadGeneration)return;frameCallback=null;
    observed={generation,mediaTime:metadata.mediaTime,frameSeq:++frameSerial};presented();observe();});
}
function cancelFrame(){if(frameCallback!==null)video.cancelVideoFrameCallback?.(frameCallback);frameCallback=null;observed=null;}
async function configValid(){
  const saved=await chrome.storage.session.get(['enabled','parserOrigin','parserRevision']);
  return saved.enabled===true&&saved.parserOrigin===session?.origin&&saved.parserRevision===session?.revision
    &&await chrome.permissions.contains({origins:[parserOriginPattern(session.origin),...CDN_ORIGINS]});
}
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const identity=m=>{try{const u=new URL(m.origin);return ['http:','https:'].includes(u.protocol)&&u.origin===m.origin&&!u.username&&!u.password&&uuid.test(m.connectionId)&&uuid.test(m.revision);}catch{return false;}};
const same=m=>session&&m?.origin===session.origin&&m.connectionId===session.connectionId&&m.revision===session.revision;
const attest=()=>{
  if(phase!=='ACTIVE'||revoking||!session||!candidate||proofPort||!challenge)return;
  const next=candidate;candidate=null;
  // A transferred page port remains untrusted until the worker supplies its
  // authenticated owner origin and connection. The exact parent must match.
  if(next.origin!==session.origin||!same(next.data)){next.port.close();return;}
  proofPort=next.port;
  proofPort.onmessage=e=>{const m=e.data;if(m?.token!==location.hash.slice(1)||!same(m))return;
    if(m.type==='DISPOSE'||m.type==='CLEAR_STATIC'&&m.frameOwnerId===bitmapOwner)revoked('DISPOSED');
    else if(m.type==='CLEAR_PRESENTATION'&&authorized&&m.frameOwnerId===frameOwnerId){clearBitmap();frameOwnerId='';desired=null;targetSeq=-1;}
    else if(m.type==='COMMIT_PRESENTATION'&&authorized&&m.frameOwnerId===frameOwnerId&&m.context===context&&m.targetSeq===targetSeq&&validFrame()){
      if(handoff){clearBitmap();phase='ACTIVE';}capture();
    }
  };
  attested=true;proofPort.postMessage({type:'BOUND',token:location.hash.slice(1),challenge,...session});
};
const bindTimer=setTimeout(()=>{if(!authorized){revoked('CONNECTION_LOST');port.disconnect();}},6000);
window.addEventListener('message',event=>{
  if(event.source!==window.parent||event.data?.channel!=='MIRTH_G0_WINDOW_BIND'
    ||event.data.token!==location.hash.slice(1)||event.ports.length!==1)return;
  if(revoking||proofPort||candidate){event.ports[0].close();return;}
  candidate={origin:event.origin,data:event.data,port:event.ports[0]};attest();
});
function reset(){loadGeneration++;cancelFrame();video.pause();try{engine?.reset();}catch{}engine=null;if(blob)URL.revokeObjectURL(blob);blob=null;video.removeAttribute('src');video.load();desired=null;pendingSeek=false;}
function retire(){
  if(revoking||phase==='RETIRING'||phase==='STATIC')return;
  capture();const keep=!!bitmapOwner&&bitmapOwner===frameOwnerId&&(!canvas.hidden||desired?.paused&&desired.retainPausedFrame===true);phase='RETIRING';authorized=false;clearTimeout(bindTimer);reset();
  if(!keep){revoked('CONNECTION_LOST');return;}canvas.hidden=false;video.style.opacity='0';notice.hidden=true;
  const ids=[...ruleIds];
  let timeout;
  cleanup=(async()=>{try{
    await Promise.race([(async()=>{if(ids.length)await chrome.declarativeNetRequest.updateSessionRules({removeRuleIds:ids});if(!await configValid())throw Error('REVOKED');})(),new Promise((_,reject)=>{timeout=setTimeout(()=>reject(Error('CLEANUP_TIMEOUT')),1000);})]);
    if(revoking)return;ruleIds=[];phase='STATIC';proof({type:'STATIC_CLEANED',frameOwnerId:bitmapOwner,cleaned:true,hasFrame:true});
  }catch(error){revoked(error?.message==='REVOKED'?'REVOKED':'CLEANUP_FAILED');}finally{clearTimeout(timeout);}})();
}
function revoked(reason='REVOKED'){
  if(!['REVOKED','DISPOSED','CONNECTION_LOST','CLEANUP_FAILED'].includes(reason))reason='REVOKED';
  if(reason==='REVOKED'||!destructionReason)destructionReason=reason;
  if(revoking)return;revoking=true;
  phase='DESTROYED';clearBitmap();
  clearTimeout(bindTimer);authorized=false;candidate?.port.close();candidate=null;reset();notice.hidden=false;notice.textContent='授权已失效，请刷新并重新启用';
  // This renderer can only clean IDs received from its authenticated worker.
  // Keep the private channel alive until the browser accepts that cleanup.
  const oldRuleIds=ruleIds;ruleIds=[];
  (async()=>{
    try{if(oldRuleIds.length)await chrome.declarativeNetRequest.updateSessionRules({removeRuleIds:oldRuleIds});
      proof({type:'CLEANED',reason:destructionReason,frameOwnerId});
    }catch{}finally{proofPort?.close();proofPort=null;ruleIds=[];}
  })();
}
function status(error=null,event='timeupdate'){
  if(!authorized)return;
  const ranges=r=>Array.from({length:Math.min(r.length,32)},(_,i)=>[r.start(i),r.end(i)]);
  port.postMessage({type:'STATUS',...session,context,event,position:video.currentTime,duration:Number.isFinite(video.duration)?video.duration:0,
    paused:video.paused,readyState:video.readyState,seeking:video.seeking,ended:video.ended,
    buffered:ranges(video.buffered),seekable:ranges(video.seekable),error});
}
function sync(){
  if(!authorized||!desired)return;
  video.playbackRate=desired.rate;video.volume=desired.volume;
  if(pendingSeek&&video.readyState>=1&&!video.seeking){pendingSeek=false;if(Math.abs(video.currentTime-desired.position)>.5)video.currentTime=desired.position;}
  if(desired.paused)video.pause();else if(video.readyState>=3&&video.paused)video.play().catch(()=>status('AUTOPLAY_BLOCKED'));
  presented();
}
port.onDisconnect.addListener(retire);
port.onMessage.addListener(m=>{
  if(m.type==='REVOKED'){if(!session||same(m))revoked();return;}
  if(m.type==='RELEASED'&&same(m)&&m.reason==='RECOVERY_RESET'){retire();return;}
  if(revoking)return;
  if(phase==='STATIC'||phase==='RETIRING')return;
  if(m.type==='WINDOW_CHALLENGE'&&identity(m)&&uuid.test(m.challenge)){
    if(session&&(!same(m)||challenge!==m.challenge))return;
    session={origin:m.origin,revision:m.revision,connectionId:m.connectionId};challenge=m.challenge;attest();return;
  }
  if(m.type==='AUTHORIZED'){
    if(authorized||!attested||!same(m)||m.protocol!==1||!Array.isArray(m.ruleIds)||m.ruleIds.length!==2||new Set(m.ruleIds).size!==2||!m.ruleIds.every(id=>Number.isSafeInteger(id)&&id>0&&id<=2147483647))return;
    ruleIds=[...m.ruleIds];clearTimeout(bindTimer);authorized=true;notice.hidden=true;notice.textContent='';return;
  }
  if(!authorized||!same(m))return;
  const {origin,revision,connectionId,...command}=m;
  if(!validateCommand(command)||m.seq<=seq)return;seq=m.seq;
  if(m.type==='STOP'){context=m.context;clearBitmap();frameOwnerId='';reset();return;}
  if(m.type==='RESET_SOURCE'){if(m.context!==context||m.frameOwnerId!==frameOwnerId)return;capture();if(bitmapOwner){handoff=true;canvas.hidden=false;video.style.opacity='.001';}reset();return;}
  if(m.type==='LOAD') {
    if(m.frameOwnerId!==bitmapOwner)clearBitmap();
    reset();context=m.context;frameOwnerId=m.frameOwnerId||'';const generation=loadGeneration;
    if(bitmapOwner){handoff=true;canvas.hidden=false;video.style.opacity='.001';}observe();
    if(m.source.kind==='mp4'){video.src=m.source.url;video.load();}
    else {
      try {
        const tracks=chooseDashTracks({dash:m.source},{mode:'auto'});
        blob=URL.createObjectURL(new Blob([createDashManifest(m.source,tracks.videoTracks,tracks.audioTracks)],{type:'application/dash+xml'}));
        engine=globalThis.dashjs.MediaPlayer().create();
        engine.updateSettings({debug:{logLevel:0},streaming:{abr:{autoSwitchBitrate:{video:false,audio:false}}}});
        engine.on(globalThis.dashjs.MediaPlayer.events.ERROR,()=>{if(generation===loadGeneration)status('LOAD_FAILED');});
        engine.initialize(video,blob,false);
      }catch{status('LOAD_FAILED');}
    }
  }else if(m.type==='SYNC'&&m.context===context&&m.frameOwnerId=== (frameOwnerId||undefined)){
    const positionChanged=!desired||Math.abs(desired.position-m.position)>.001;
    desired=m;targetSeq=m.seq;
    if(bitmapOwner&&!handoff&&(positionChanged||!m.paused||!m.retainPausedFrame))clearBitmap();
    pendingSeek=true;sync();observe();
  }
});
chrome.storage?.onChanged.addListener((changes,area)=>{
  if(area!=='session')return;
  if(changes.enabled&&changes.enabled.newValue!==true||changes.parserOrigin&&changes.parserOrigin.newValue!==session?.origin||changes.parserRevision&&changes.parserRevision.newValue!==session?.revision){revoked();return;}
  if(['enabled','parserOrigin','parserRevision'].some(k=>Object.hasOwn(changes,k)))configValid().then(valid=>{if(!valid)revoked();}).catch(revoked);
});
chrome.permissions?.onRemoved.addListener(removed=>{
  const required=session?[parserOriginPattern(session.origin),...CDN_ORIGINS,'<all_urls>',new URL(session.origin).protocol+'//*/*']:CDN_ORIGINS;
  if(removed?.origins?.some(origin=>required.includes(origin))){revoked();return;}
  configValid().then(valid=>{if(!valid)revoked();}).catch(revoked);
});
for(const event of ['canplay','loadedmetadata','seeked'])video.addEventListener(event,()=>{sync();status(null,event);});
for(const event of ['play','playing','pause','waiting','ended','timeupdate','enterpictureinpicture','leavepictureinpicture'])video.addEventListener(event,()=>status(null,event));
video.addEventListener('error',()=>status('LOAD_FAILED'));
window.addEventListener('pagehide',()=>{revoked();port.disconnect();});
port.postMessage({type:'RENDERER_READY'});
