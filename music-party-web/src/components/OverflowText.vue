<template><component :is="as" ref="container" class="overflow-text" :class="{'reduced-motion':reduced}" :title="text" :aria-label="text"><span ref="content" class="overflow-text-content">{{ text }}</span></component></template>
<script setup>
import {ref,toRef} from 'vue';
import {useOverflowText} from '../composables/useOverflowText.js';
const props=defineProps({text:{type:String,default:''},as:{type:String,default:'span'}});
const container=ref(null),content=ref(null);const {reduced}=useOverflowText(container,content,toRef(props,'text'));
</script>
<style scoped>
.overflow-text{display:block;min-width:0;max-width:100%;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.overflow-text-content{display:inline-block;white-space:nowrap;width:max-content;max-width:none;vertical-align:bottom}
.overflow-text.reduced-motion{white-space:normal;overflow:visible;overflow-wrap:anywhere}.overflow-text.reduced-motion .overflow-text-content{white-space:normal;width:auto;max-width:100%}
</style>
