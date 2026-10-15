<script setup>
import { CircleHelp } from 'lucide-vue-next';
defineEmits(['click']);
</script>
<template>
  <button type="button" aria-label="帮助" title="帮助" class="help-icon-button" @click="$emit('click', $event)"><CircleHelp class="w-4 h-4" aria-hidden="true" /></button>
</template>
<style scoped>
.help-icon-button{display:flex;align-items:center;justify-content:center;width:36px;height:36px;border:1px solid rgb(var(--medical-200));background:rgb(var(--medical-50));color:rgb(var(--medical-700));border-radius:2px}.help-icon-button:hover{background:rgb(var(--medical-100))}.help-icon-button:focus-visible{outline:2px solid rgb(var(--accent));outline-offset:2px}@media(pointer:coarse){.help-icon-button{width:44px;height:44px}}
</style>
