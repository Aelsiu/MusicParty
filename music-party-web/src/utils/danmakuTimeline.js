import { danmakuDuration } from './danmakuSettings.js';
// Cursor follows media time. A late segment starts at now, never at its beginning.
export function createDanmakuTimeline() {
    let data=null, cursor=0, previous=null, visible=[], laneCount=12, speed=1;
    const after=(rows,time)=>{let low=0,high=rows.length;while(low<high){const mid=(low+high)>>1;if(rows[mid].progressMs<=time*1000)low=mid+1;else high=mid;}return low;};
    const lanesFor=(mode,count)=>mode===4 ? Array.from({length:Math.ceil(count/2)},(_,i)=>count-1-i) : mode===5 ? Array.from({length:Math.ceil(count/2)},(_,i)=>i) : Array.from({length:count},(_,i)=>i);
    return {
        clear() {data=null;cursor=0;previous=null;visible=[];},
        align(from,time) {
            if(previous===null) return [];
            // Synchronization corrects the clock, not the age of text already in
            // flight. Rebase its origin so position and expiry remain continuous.
            const correction=time-from;
            visible=visible.map(entry=>({...entry,start:entry.start+correction}));
            if(data) cursor=Math.max(cursor,after(data,time));
            previous=time;
            return visible.slice();
        },
        frame(rows,time,paused,layout={}) {
            const nextCount=Math.max(1,Math.min(12,Math.floor(layout.laneCount||12))),nextSpeed=layout.speed||1;
            if(nextSpeed!==speed && previous!==null) visible=visible.map(entry=>{
                const duration=danmakuDuration(entry,nextSpeed);
                return {...entry,start:previous-(previous-entry.start)*duration/entry.duration,duration};
            });
            if(nextCount!==laneCount) {
                const reflow=[];
                for(const entry of visible) {
                    const lane=lanesFor(entry.mode,nextCount).find(i=>!reflow.some(item=>item.lane===i));
                    if(lane!==undefined) reflow.push({...entry,lane});
                }
                visible=reflow;
            }
            laneCount=nextCount;speed=nextSpeed;
            if(previous===null || time<previous) {visible=[];data=rows;cursor=after(rows,time);}
            else if(rows!==data) {data=rows;cursor=after(rows,time);}
            // A slow frame is not a seek. Keep text still within its lifetime,
            // but position the cursor after the missed window instead of bursting.
            else if(time-previous>.75) cursor=after(rows,time);
            visible=visible.filter(entry=>time-entry.start<entry.duration);
            if(!paused && previous!==null && time>previous) {
                while(cursor<rows.length && rows[cursor].progressMs<=time*1000) {
                    const entry=rows[cursor++];
                    if(entry.progressMs<=previous*1000 || visible.length>=80) continue;
                    const lanes=lanesFor(entry.mode,laneCount);
                    const lane=lanes.find(i=>!visible.some(item=>item.lane===i));
                    if(lane!==undefined) visible.push({...entry,lane,start:entry.progressMs/1000,duration:danmakuDuration(entry,speed)});
                }
            }
            previous=time;return visible.slice();
        }
    };
}
