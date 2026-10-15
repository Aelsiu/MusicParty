// Isolated top-level bridge. Presentation identity never grants media access.
(() => {
  if(window!==window.top||!['http:','https:'].includes(location.protocol))return;
  const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  let current=null,retained=null,retiring=null;
  const destroyedSlots=new Set();
  const notify=m=>window.postMessage({channel:'MIRTH_EMBEDDED_PLAYER',...m},location.origin);
  const identity=s=>({origin:location.origin,connectionId:s.connectionId,revision:s.revision});
  const same=(m,s)=>m?.origin===location.origin&&m.connectionId===s.connectionId&&m.revision===s.revision;
  const privateSend=(s,m)=>{try{s.proof?.postMessage({token:s.token,...identity(s),...m});}catch{}};
  const remove=s=>{if(!s)return;destroyedSlots.delete(s);clearTimeout(s.timer);s.ready=false;s.proof?.close();s.frame?.remove();if(retained===s)retained=null;if(retiring===s)retiring=null;};
  const destroy=s=>{if(!s||s.destroyed)return;s.destroyed=true;destroyedSlots.add(s);privateSend(s,{type:'DISPOSE'});try{s.port.disconnect();}catch{}
    s.timer=setTimeout(()=>remove(s),1000);if(!s.proof)remove(s);
  };
  const clear=owner=>{
    for(const s of new Set([current,retained,retiring]))if(s&&(!owner||s.frameOwnerId===owner)){
      if(owner&&current===s){privateSend(s,{type:'CLEAR_PRESENTATION',frameOwnerId:owner});s.frameOwnerId='';s.targetSeq=-1;}
      else{if(current===s)current=null;destroy(s);}
    }
  };
  function suspend(s,release=false){
    if(current!==s)return;current=null;s.ready=false;
    if(retiring&&retiring!==s)destroy(retiring);retiring=s;
    s.timer=setTimeout(()=>{if(retiring===s)destroy(s);},1200);
    if(release){try{s.port.postMessage({type:'RELEASE_FOR_RECOVERY',frameOwnerId:s.frameOwnerId,connectionId:s.connectionId,revision:s.revision});}catch{destroy(s);}}
    notify({type:'UNAVAILABLE',...identity(s)});
  }
  function receiveProof(s,m){
    if(m?.token!==s.token||!same(m,s))return;
    if(m.type==='CLEANED'){
      if(!s.destroyed&&m.reason==='REVOKED'&&uuid.test(m.frameOwnerId)&&m.frameOwnerId===s.frameOwnerId)notify({type:'REVOKED',frameOwnerId:m.frameOwnerId,...identity(s)});
      remove(s);return;
    }
    if(s.destroyed)return;
    if(m.type==='BOUND'&&uuid.test(m.challenge)&&current===s){s.port.postMessage({type:'BIND_RENDERER',token:s.token,challenge:m.challenge,connectionId:s.connectionId,revision:s.revision});return;}
    if(m.type==='STATIC_CLEANED'){
      if(retiring!==s||!s.frameOwnerId||m.frameOwnerId!==s.frameOwnerId||m.cleaned!==true||m.hasFrame!==true)return;
      clearTimeout(s.timer);retiring=null;if(retained&&retained!==s)destroy(retained);retained=s;s.frame.style.opacity='1';
      if(current?.frame)current.frame.style.opacity='.001';return;
    }
    if(m.type==='FRAME_PRESENTED'&&current===s&&s.ready&&m.frameOwnerId===s.frameOwnerId&&m.context===s.context&&m.targetSeq===s.targetSeq
      &&Number.isSafeInteger(m.frameSeq)&&m.frameSeq>0&&Number.isFinite(m.mediaTime)&&Math.abs(m.mediaTime-s.position)<=.5
      &&Number.isFinite(m.width)&&m.width>0&&m.width<=16384&&Number.isFinite(m.height)&&m.height>0&&m.height<=16384){
      privateSend(s,{type:'COMMIT_PRESENTATION',frameOwnerId:s.frameOwnerId,context:s.context,targetSeq:s.targetSeq});
      s.frame.style.opacity='1';if(retained?.frameOwnerId===s.frameOwnerId)destroy(retained);
      if(retiring?.frameOwnerId===s.frameOwnerId)destroy(retiring);
    }
  }
  window.addEventListener('pagehide',()=>clear());
  window.addEventListener('message',event=>{
    const m=event.data;
    if(event.source!==window||event.origin!==location.origin||m?.channel!=='MIRTH_EMBEDDED_PLAYER_COMMAND')return;
    if(m.type==='CLEAR_PRESENTATION'){if(m.origin===location.origin&&uuid.test(m.frameOwnerId))clear(m.frameOwnerId);return;}
    if(m.type==='CONNECT'){
      if(!uuid.test(m.connectionId)||m.frameOwnerId!==undefined&&(typeof m.frameOwnerId!=='string'||!uuid.test(m.frameOwnerId))||current?.connectionId===m.connectionId)return;
      const host=document.querySelector('[data-mirth-extension-player]');
      if(!host){notify({type:'UNAVAILABLE',origin:location.origin,connectionId:m.connectionId,revision:null});return;}
      if(current){const old=current;current=null;destroy(old);}
      const connection=chrome.runtime.connect({name:'mirth-owner'});
      const s={port:connection,connectionId:m.connectionId,revision:null,ready:false,lastSeq:-1,token:'',frame:null,proof:null,frameOwnerId:m.frameOwnerId||'',context:'',targetSeq:-1};current=s;
      connection.onDisconnect.addListener(()=>{if(current===s)suspend(s);});
      connection.onMessage.addListener(reply=>{
        if(s.destroyed)return;
        if(reply?.connectionId!==s.connectionId||reply.origin!==location.origin||!uuid.test(reply.revision)||s.revision!==null&&reply.revision!==s.revision)return;
        if(reply.type==='REVOKED'){if(current===s)current=null;destroy(s);notify({...reply,...(uuid.test(s.frameOwnerId)?{frameOwnerId:s.frameOwnerId}:{})});return;}
        if(reply.type==='RELEASED'&&reply.reason==='RECOVERY_RESET')return;
        if(current!==s||s.destroyed)return;
        if(reply.type==='MOUNT'){
          if(s.frame||reply.protocol!==1||typeof reply.url!=='string')return;
          let url;try{url=new URL(reply.url);}catch{return;}
          if(reply.url.slice(0,reply.url.indexOf('#'))!==chrome.runtime.getURL('renderer.html')||url.search||!uuid.test(url.hash.slice(1)))return;
          s.revision=reply.revision;s.token=url.hash.slice(1);
          // Closed transports no longer own media rules; their worker cleanup
          // is serialized before this grant. Never accumulate dying iframes.
          for(const old of [...destroyedSlots])remove(old);
          if(retained&&retiring){destroy(retiring);remove(retiring);}
          s.frame=document.createElement('iframe');s.frame.src=reply.url;s.frame.title='MirthParty 扩展播放器';s.frame.tabIndex=-1;
          s.frame.onload=()=>{
            if(current!==s||s.destroyed)return;
            const channel=new MessageChannel();s.proof?.close();s.proof=channel.port1;s.proof.onmessage=e=>receiveProof(s,e.data);
            s.frame.contentWindow.postMessage({channel:'MIRTH_G0_WINDOW_BIND',token:s.token,...identity(s)},chrome.runtime.getURL('').replace(/\/$/,''),[channel.port2]);
          };
          s.frame.allow='autoplay; fullscreen; picture-in-picture';s.frame.style.cssText='position:absolute;inset:0;width:100%;height:100%;border:0;display:block;pointer-events:none';
          s.frame.style.opacity=retained||retiring?'.001':'1';host.append(s.frame);
        }else if(s.revision!==null){if(reply.type==='READY'&&reply.protocol===1)s.ready=true;notify(reply);}
      });
      connection.postMessage({type:'HELLO',connectionId:s.connectionId});return;
    }
    const s=current;if(!s||m.connectionId!==s.connectionId)return;
    if(m.type==='DISCONNECT'){if(m.revision===s.revision||m.revision===null){current=null;destroy(s);if(retained)destroy(retained);if(retiring)destroy(retiring);}return;}
    if(m.type==='RESET_CONNECTION'){if((m.revision===s.revision||m.revision===null)&&uuid.test(m.frameOwnerId)&&m.frameOwnerId===s.frameOwnerId){suspend(s,s.revision!==null);if(s.revision===null)try{s.port.disconnect();}catch{}}return;}
    if(!s.ready||m.revision!==s.revision)return;
    if(!Number.isSafeInteger(m.seq)||m.seq<=s.lastSeq)return;
    if(m.frameOwnerId!==undefined&&(typeof m.frameOwnerId!=='string'||!uuid.test(m.frameOwnerId)))return;
    if(m.type==='LOAD'){
      s.frameOwnerId=m.frameOwnerId||'';s.context=m.context;s.targetSeq=-1;
      for(const old of [retained,retiring])if(old&&old.frameOwnerId!==s.frameOwnerId)destroy(old);
    }
    if(m.type==='SYNC'){if(m.frameOwnerId!==(s.frameOwnerId||undefined)||m.frameOwnerId&&m.context!==s.context)return;s.targetSeq=m.seq;s.position=m.position;}
    if(m.type==='STOP'){s.frameOwnerId='';for(const old of [retained,retiring])if(old)destroy(old);}
    s.lastSeq=m.seq;const {channel,...command}=m;s.port.postMessage(command);
  });
})();
