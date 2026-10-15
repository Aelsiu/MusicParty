<template>
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M16 5H3" />
    <path d="M11 12H3" />
    <path d="M16 19H3" />
    <template v-if="variant === 'all'">
      <path d="M15 15l3-6 3 6" stroke-width="1.5" />
      <path d="M16 13h4" stroke-width="1.5" />
    </template>
    <path v-else d="M15 12l2 2 4-4" />
  </svg>
</template>

<script setup>
defineProps({ variant: { type: String, required: true } });
</script>
