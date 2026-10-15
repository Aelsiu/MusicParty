<template><div ref="container" class="room-sidebar" :style="{'--room-sidebar-height':`${height}px`}"><div class="room-members-scroll"><UserList /></div><ChatOverlay :visible="visible" /></div></template>
<script setup>
import {ref,onMounted,onBeforeUnmount} from 'vue';
import UserList from './UserList.vue';
import ChatOverlay from './ChatOverlay.vue';
defineProps({visible:Boolean});
const container=ref(null),height=ref(400);let observer;
onMounted(()=>{if(typeof ResizeObserver!=='undefined'){observer=new ResizeObserver(entries=>{height.value=entries[0].contentRect.height;});observer.observe(container.value);}});
onBeforeUnmount(()=>observer?.disconnect());
</script>
<style scoped>
.room-sidebar{position:relative;width:100%;height:100%;min-height:0;overflow:hidden}.room-members-scroll{height:calc(100% - 72px);overflow-y:auto;overscroll-behavior:contain}
@media(max-width:767px){.room-members-scroll{height:calc(100% - 76px - env(safe-area-inset-bottom))}}
</style>
