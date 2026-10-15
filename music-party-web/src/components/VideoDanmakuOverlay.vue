<template>
  <div ref="layer" data-danmaku-overlay class="absolute inset-0 pointer-events-none overflow-hidden" aria-hidden="true" :style="{opacity:layout.settings.opacity/100,clipPath:`inset(0 0 ${100-layout.settings.area}% 0)`}">
    <span v-for="entry in entries" :key="entry.id" data-danmaku-entry class="absolute whitespace-nowrap font-bold" :style="style(entry)">{{ entry.text }}</span>
  </div>
</template>
<script setup>
import {ref,computed,onMounted,onBeforeUnmount} from 'vue';
import {danmakuLayout,danmakuDuration} from '../utils/danmakuSettings.js';
const props=defineProps({entries:Array,mediaTime:Number,settings:Object});
const emit=defineEmits(['resize']);
const layer=ref(null),width=ref(640),height=ref(360);let observer;
const layout=computed(()=>danmakuLayout(height.value,props.settings));
const style=entry=>{
  const fixed=entry.mode===4 || entry.mode===5, progress=Math.min(1,Math.max(0,(props.mediaTime-entry.start)/(entry.duration||danmakuDuration(entry,layout.value.settings.speed))));
  const fontSize=layout.value.fontSize(entry.fontSize),textWidth=Array.from(entry.text).length*fontSize;
  const x=fixed ? width.value/2 : entry.mode===6 ? -textWidth+progress*(width.value+textWidth) : width.value-progress*(width.value+textWidth);
  return {left:`${x}px`,top:`${entry.lane*layout.value.rowHeight}px`,transform:fixed?'translateX(-50%)':undefined,
    fontSize:`${fontSize}px`,lineHeight:1.4,color:`#${entry.color.toString(16).padStart(6,'0')}`,textShadow:'1px 1px 2px #000'};
};
onMounted(()=>{observer=new ResizeObserver(([entry])=>{width.value=entry.contentRect.width;height.value=entry.contentRect.height;emit('resize',{width:width.value,height:height.value});});observer.observe(layer.value);});
onBeforeUnmount(()=>observer?.disconnect());
</script>
