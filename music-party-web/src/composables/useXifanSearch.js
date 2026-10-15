import { ref, watch, onBeforeUnmount } from 'vue';
import { videoApi, matchesVideoRead } from '../api/video';
import { roomSession } from '../services/roomSession';
import { useVideoFavoriteImport } from './useVideoFavoriteImport';

export function useXifanSearch(player, user, keyword, platform, open, feedback = null) {
    const items=ref([]),page=ref(0),hasMore=ref(false),busy=ref(false),error=ref(''),picker=ref(false);
    const flow=useVideoFavoriteImport(player,user,'XIFAN_EPISODES',feedback);
    let sequence=0,abort=null,alive=true;
    const invalidate=()=>{sequence++;abort?.abort();abort=null;busy.value=false;error.value='';items.value=[];page.value=0;hasMore.value=false;picker.value=false;flow.clearRead();};
    const closePicker=()=>{sequence++;abort?.abort();abort=null;busy.value=false;error.value='';picker.value=false;flow.clearRead();};
    const capture=()=>{
        abort?.abort();abort=new AbortController();const request=++sequence,context=player.playbackContext(),room=roomSession.roomId,generation=roomSession.generation,input=keyword.value;
        const current=()=>alive&&request===sequence&&open.value&&platform.value==='xifan'&&player.synchronized&&player.mediaMode==='VIDEO'&&room===roomSession.roomId&&generation===roomSession.generation&&keyword.value===input&&player.isContextCurrent(context);
        return {context,room,current,signal:abort.signal};
    };
    const search=async(next=1)=>{
        if(!player.videoFeatures.xifanSearch||player.config.xifanEnabled===false)return;
        const work=capture();if(!work.context){feedback?.fail('search','房间连接尚未就绪');return;}busy.value=true;error.value='';if(next===1){items.value=[];page.value=0;hasMore.value=false;picker.value=false;flow.clearRead();}
        const ticket=feedback?.begin('search');
        try{const response=await videoApi.xifanSearch(keyword.value.trim(),next,work.context,work.signal);if(!work.current())return;if(!matchesVideoRead(response,work.context,player,work.room)){error.value='搜索响应已过期，请重试';return;}
            items.value=next===1?response.items:[...items.value,...response.items];page.value=next;hasMore.value=response.hasMore;
            feedback?.finish(ticket,true);
        }catch(e){if(work.current())error.value=e.response?.data?.message||'XIFAN 搜索失败，请重试';}
        finally{if(work.current()){busy.value=false;if(error.value)feedback?.finish(ticket,false,error.value);}}
    };
    const preview=async(animeId,sourceId)=>{
        const work=capture();if(!work.context){feedback?.fail('load','房间连接尚未就绪');return;}busy.value=true;error.value='';
        picker.value=true;const ticket=feedback?.begin('load');
        try{const response=await videoApi.xifanPreview(animeId,sourceId,work.context,work.signal);if(!work.current())return;if(!matchesVideoRead(response,work.context,player,work.room)){error.value='读取响应已过期，请重试';return;}flow.acceptPreview({...response,page:1});picker.value=true;
        }catch(e){if(work.current())error.value=e.response?.data?.message||'XIFAN 选集失败，请重试';}finally{if(work.current()){busy.value=false;feedback?.finish(ticket,!error.value,error.value);}}
    };
    const resolve=async()=>{
        if(!/https?:\/\//.test(keyword.value))return search();
        const work=capture();if(!work.context){feedback?.fail('search','房间连接尚未就绪');return;}busy.value=true;error.value='';
        const ticket=feedback?.begin('search');
        try{const response=await videoApi.resolve(keyword.value,work.context,work.signal,'xifan');if(!work.current())return;if(!matchesVideoRead(response,work.context,player,work.room)){error.value='搜索响应已过期，请重试';return;}
            if(response.type==='KEYWORD'){await search();return;}flow.acceptPreview({...response,page:1});picker.value=true;
        }catch(e){if(work.current())error.value=e.response?.data?.message||'请检查 XIFAN 官网链接，或切换对应平台';}finally{if(work.current()){busy.value=false;feedback?.finish(ticket,!error.value,error.value);}}
    };
    watch([platform,keyword,open,()=>roomSession.generation,()=>player.clientGeneration,()=>player.playbackEpoch,()=>player.modeEpoch,()=>player.mediaMode,()=>player.synchronized],invalidate,{flush:'sync'});
    watch(open,value=>{if(value && platform.value==='xifan')queueMicrotask(()=>{if(alive && open.value && platform.value==='xifan')resolve();});});
    onBeforeUnmount(()=>{alive=false;invalidate();});
    return {items,page,hasMore,busy,error,picker,flow,search,preview,resolve,invalidate,closePicker};
}
