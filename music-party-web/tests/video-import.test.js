import test from 'node:test';
import assert from 'node:assert/strict';
import { register } from 'node:module';
import { createRenderer, defineComponent, reactive, nextTick } from 'vue';
register(new URL('./helpers/source-resolution.js',import.meta.url));
globalThis.localStorage={getItem:()=>null,setItem(){},removeItem(){}};
globalThis.sessionStorage=globalThis.localStorage;
globalThis.window={location:{pathname:'/',origin:'http://localhost',search:'',hash:''},history:{pushState(){},replaceState(){}},dispatchEvent(){}};
const {useVideoFavoriteImport}=await import('../src/composables/useVideoFavoriteImport.js');
const {videoApi}=await import('../src/api/video.js');
const {roomSession}=await import('../src/services/roomSession.js');
const renderer=createRenderer({createComment:()=>({}),insert(){},remove(){},createElement:()=>({}),createText:()=>({}),setText(){},setElementText(){},parentNode(){},nextSibling(){},patchProp(){}});
function mount(t){
 roomSession.roomId='room'; let id=0; const sent=[],queries=[];
 const player=reactive({videoFeatures:{favoriteLinkImport:true},config:{maxPlaylistImportSize:100,maxQueueSize:10,maxUserSongs:10},queue:[],videoImports:{},synchronized:true,clientGeneration:1,modeEpoch:1,playbackEpoch:'epoch',playbackContext:()=>({requestId:'r'+(++id),clientGeneration:1,expectedPlaybackEpoch:'epoch',expectedMode:'VIDEO',expectedModeEpoch:1}),isContextCurrent:c=>c.clientGeneration===player.clientGeneration&&c.expectedModeEpoch===player.modeEpoch,sendMedia:(dest,payload,context)=>{sent.push({dest,payload,context});return true;},queryVideoImport:query=>queries.push(query)});
 let flow;const app=renderer.createApp(defineComponent({setup(){flow=useVideoFavoriteImport(player,{isGuest:false,userToken:'user'});return()=>null;}}));app.mount({});t.after(()=>app.unmount());return{flow,player,sent,queries};
}
test('favorite selection is immutable in-flight; unknown ACK queries original batch; terminal retry excludes successful items',async t=>{
 const {flow,player,sent,queries}=mount(t);
 flow.preview.value={previewId:'preview'};flow.items.value=[{itemId:'1',selectable:true},{itemId:'2',selectable:true}];flow.selectLoaded();flow.submit();
 assert.deepEqual(sent[0].payload.items,[{itemId:'1',page:1},{itemId:'2',page:1}]);flow.toggle(flow.items.value[0],false);
 assert.equal(sent[0].payload.items.length,2);flow.submit();assert.equal(sent.length,1);assert.equal(queries.at(-1).requestId,'r1');
 player.videoImports.r1={status:'PARTIAL',items:[{itemId:'1',status:'ADDED',retryable:false},{itemId:'2',status:'TEMPORARY_FAILURE',retryable:true}]};await nextTick();
 assert.equal(flow.selected.value['1'],undefined);assert.equal(flow.retryable('1'),false);assert.equal(flow.retryable('2'),true);
 flow.submit();assert.equal(sent.length,2);assert.equal(sent[1].context.requestId,'r2');assert.deepEqual(sent[1].payload.items,[{itemId:'2',page:1}]);
});
test('favorite late HTTP response after mode switch is discarded, not shown as empty success',async t=>{
 const {flow,player}=mount(t);let finish;const old=videoApi.preview;videoApi.preview=()=>new Promise(resolve=>finish=resolve);t.after(()=>videoApi.preview=old);
 const loading=flow.load(1);player.modeEpoch=2;await nextTick();finish({protocolVersion:3,roomId:'room',requestId:'r1',playbackEpoch:'epoch',mediaMode:'VIDEO',modeEpoch:1,previewId:'old',page:1,items:[]});await loading;
 assert.equal(flow.preview.value,null);assert.deepEqual(flow.items.value,[]);
});

test('unavailable original result remains uncertain and blocks new-key replay until explicit queue check and new preview',async t=>{
 const {flow,player,sent,queries}=mount(t);flow.preview.value={previewId:'preview'};flow.items.value=[{itemId:'1',selectable:true}];flow.selectLoaded();flow.submit();
 player.videoImports.r1={requestId:'r1',kind:'IMPORT_ERROR',code:'IMPORT_RESULT_UNAVAILABLE',status:'UNKNOWN'};await nextTick();
 assert.equal(flow.running.value,true);flow.submit();assert.equal(sent.length,1);assert.equal(queries.at(-1).requestId,'r1');
 flow.acknowledgeQueueCheck();assert.equal(flow.preview.value,null);assert.equal(flow.selection.value.length,0);flow.submit();assert.equal(sent.length,1);
});
