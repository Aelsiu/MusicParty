import {buildMetadata} from './build-metadata.js';
import {normalizeParserOrigin,parserOriginPattern,validParserIdentity} from './parserConfig.js';

function popupError(code) {
  if(code==='INVALID_PARSER_ORIGIN')return '请输入有效的 http:// 或 https:// 网站地址';
  if(code==='PERMISSION_DENIED'||code==='PARSER_PERMISSION_REQUIRED')return '未获得该网站访问权限，请重新启用并允许访问';
  if(code==='RULES_CLEANUP_FAILED'||code==='PARSER_CLEANUP_FAILED')return '停用未完成，请重试';
  if(code==='STATE_UNAVAILABLE')return '无法确认解析器状态，请重新打开扩展';
  if(code==='CURRENT_PAGE_UNAVAILABLE')return '无法获取当前页面的网站地址，请手动输入 HTTP(S) 地址';
  if(code==='DRAFT_STORAGE_FAILED')return '地址草稿未能保存，重新打开扩展后可能需要再次输入';
  return '操作未完成，请重试或重新打开扩展';
}

function parseState(reply) {
  if(!reply||typeof reply.enabled!=='boolean'||typeof reply.origin!=='string'
    ||(!validParserIdentity(reply.revision)&&!(reply.enabled===false&&reply.revision==='')))
    throw Error('STATE_UNAVAILABLE');
  const origin=reply.origin?normalizeParserOrigin(reply.origin):'';
  if(origin!==reply.origin||(reply.enabled&&!origin))throw Error('STATE_UNAVAILABLE');
  return {enabled:reply.enabled,origin,revision:reply.revision,error:typeof reply.error==='string'?reply.error:null};
}

export function mountParserPopup({document,chrome}) {
  const panel=document.querySelector('#panel'),address=document.querySelector('#address'),toggle=document.querySelector('#toggle');
  const error=document.querySelector('#error'),status=document.querySelector('#state');
  const currentAddress=document.querySelector('#current-address');
  document.querySelector('#version').textContent=`v${buildMetadata.displayVersion}`;
  let actual={enabled:false,origin:'',revision:''},draft='',known=false,busy=false,progress='',failure=null,invalidAddress=false;
  let operation=Promise.resolve(),refreshQueued=false,disposed=false,cleanupUnconfirmed=false;
  let editGeneration=0,draftWrites=Promise.resolve(),addressRead=Promise.resolve();
  const openingTab=Promise.resolve().then(()=>chrome.tabs.query({active:true,currentWindow:true})).then(tabs=>tabs[0]?.id).catch(()=>null);
  function saveDraft(value) {
    let origin;try{origin=value.trim()?normalizeParserOrigin(value):'';}catch{return;}
    draftWrites=draftWrites.then(()=>origin?chrome.storage.local.set({parserOriginDraft:origin}):chrome.storage.local.remove('parserOriginDraft')).catch(()=>{
      if(!disposed){failure='DRAFT_STORAGE_FAILED';render();}
    });
  }

  function render() {
    if(disposed)return;
    panel.dataset.enabled=String(known&&actual.enabled);panel.setAttribute('aria-busy',String(busy));
    address.value=draft;address.disabled=busy||!known||actual.enabled||cleanupUnconfirmed;
    currentAddress.disabled=address.disabled;
    toggle.checked=actual.enabled;toggle.disabled=busy||!known;
    if(invalidAddress)address.setAttribute('aria-invalid','true');else address.removeAttribute('aria-invalid');
    error.hidden=!failure;error.textContent=failure?popupError(failure):'';
    status.replaceChildren();
    if(busy){status.textContent=progress;return;}
    if(!known){status.textContent='无法确认解析器状态';return;}
    if(!actual.enabled){status.textContent='未启用解析器';return;}
    const link=document.createElement('a');link.href=actual.origin;link.textContent=actual.origin;link.target='_blank';link.rel='noopener noreferrer';
    status.append('已在 ',link,' 上启用解析器，请刷新页面');
  }
  function accept(reply,{preserveDraft=false}={}) {
    const next=parseState(reply);
    // Reads and changes are serialized by busy; opaque UUID revisions have no ordering.
    actual=next;known=true;
    // Disabled capability precedes cleanup in the worker. An errored STATE is
    // insufficient to confirm that old rules and the injector have been removed.
    cleanupUnconfirmed=!next.enabled&&!!next.error;
    if(next.enabled||!preserveDraft)draft=next.origin;
    return next.error;
  }
  const read=()=>chrome.runtime.sendMessage({type:'PARSER_STATE'});
  function settle() {
    busy=false;render();
    if(refreshQueued&&!disposed){refreshQueued=false;refresh({preserveDraft:true});}
  }
  function refresh({preserveDraft=true}={}) {
    if(disposed)return operation;
    if(busy){refreshQueued=true;return operation;}
    busy=true;progress='正在核对解析器状态…';render();
    const startedEdit=editGeneration;
    operation=(async()=>{
      try{
        const wasCleanupUnconfirmed=cleanupUnconfirmed;
        const reply=await read();
        failure=accept(reply,{preserveDraft:preserveDraft||startedEdit!==editGeneration})||(wasCleanupUnconfirmed||failure==='STATE_UNAVAILABLE'?null:failure);
      }
      catch{known=false;failure='STATE_UNAVAILABLE';}
      finally{settle();}
    })();
    return operation;
  }
  async function change(enabled,origin,permission) {
    try{
      if(enabled){
        let granted;try{granted=await permission;}catch{throw Error('PERMISSION_DENIED');}
        if(!granted)throw Error('PERMISSION_DENIED');
      }
      if(disposed)return;
      const ack=parseState(await chrome.runtime.sendMessage({type:'PARSER_SET',enabled,...(enabled?{origin}:{})}));
      const reply=await read();
      failure=accept(reply,{preserveDraft:true});
      if(!failure)failure=ack.error;
      if(!failure&&(actual.enabled!==enabled||(enabled&&actual.origin!==origin)))failure='CHANGE_NOT_APPLIED';
    }catch(cause){
      const code=cause?.message==='PERMISSION_DENIED'?'PERMISSION_DENIED':'PARSER_OPERATION_FAILED';
      try{failure=accept(await read(),{preserveDraft:true})||code;}
      catch{known=false;failure='STATE_UNAVAILABLE';}
    }finally{settle();}
  }
  function onToggle() {
    if(busy||!known||disposed){render();return;}
    const enabled=toggle.checked;let origin=actual.origin,permission;
    failure=null;invalidAddress=false;
    if(enabled){
      try{
        origin=normalizeParserOrigin(address.value);draft=origin;saveDraft(origin);
        // This call stays in the native checkbox gesture, before any awaited work.
        permission=chrome.permissions.request({origins:[parserOriginPattern(origin)]});
      }catch(cause){
        failure=cause?.message==='INVALID_PARSER_ORIGIN'?'INVALID_PARSER_ORIGIN':'PERMISSION_DENIED';invalidAddress=failure==='INVALID_PARSER_ORIGIN';render();return;
      }
    }
    busy=true;progress=enabled?'正在启用解析器…':'正在停用解析器…';render();
    operation=change(enabled,origin,permission);
  }
  function onInput(){++editGeneration;draft=address.value;failure=null;invalidAddress=false;saveDraft(draft);render();}
  function onCurrentAddress(){
    if(address.disabled||disposed)return;
    const generation=++editGeneration;
    addressRead=(async()=>{
      try{
        const id=await openingTab;if(id==null)throw Error();
        const tab=await chrome.tabs.get(id),origin=normalizeParserOrigin(tab.url);
        if(disposed||generation!==editGeneration||address.disabled)return;
        draft=origin;failure=null;invalidAddress=false;saveDraft(origin);render();
      }catch{if(!disposed&&generation===editGeneration&&!address.disabled){failure='CURRENT_PAGE_UNAVAILABLE';render();}}
    })();
  }
  function onChanged(changes,area){if((area==='session'||area==='local')&&['enabled','parserOrigin','parserRevision'].some(key=>Object.hasOwn(changes,key)))refresh();}
  const onRemoved=()=>refresh();
  toggle.addEventListener('change',onToggle);address.addEventListener('input',onInput);
  currentAddress.addEventListener('click',onCurrentAddress);
  chrome.permissions.onRemoved?.addListener(onRemoved);chrome.storage.onChanged?.addListener(onChanged);
  const initialEdit=editGeneration;
  const storedDraft=Promise.resolve().then(()=>chrome.storage.local.get('parserOriginDraft')).catch(()=>({}));
  const ready=(async()=>{
    await refresh({preserveDraft:false});const stored=await storedDraft;
    if(!disposed&&known&&!actual.enabled&&editGeneration===initialEdit){
      try{if(stored.parserOriginDraft)draft=normalizeParserOrigin(stored.parserOriginDraft);}catch{}
      render();
    }
  })();
  return {ready,async idle(){let pending;do{pending=operation;await pending;await addressRead;await draftWrites;}while(pending!==operation);},dispose(){
    disposed=true;toggle.removeEventListener('change',onToggle);address.removeEventListener('input',onInput);
    currentAddress.removeEventListener('click',onCurrentAddress);
    chrome.permissions.onRemoved?.removeListener(onRemoved);chrome.storage.onChanged?.removeListener(onChanged);
  }};
}

if(typeof document!=='undefined'&&globalThis.chrome?.runtime?.id)mountParserPopup({document,chrome});
