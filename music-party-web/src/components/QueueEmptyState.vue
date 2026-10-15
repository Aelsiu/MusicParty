<template>
  <div class="flex-1 text-center py-10 text-xs font-mono text-medical-400">
    QUEUE EMPTY / WAITING FOR {{ history ? 'HISTORY' : 'INPUT' }}
  </div>
</template>

<script setup>
defineProps({ history: Boolean });
</script>
