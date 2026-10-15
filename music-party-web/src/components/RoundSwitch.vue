<template>
  <button type="button" role="switch" :aria-checked="modelValue" :aria-label="label" :disabled="disabled"
          class="relative w-8 h-4 shrink-0 rounded-full transition-colors duration-300 disabled:opacity-40 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
          :class="modelValue ? 'bg-accent' : 'bg-medical-300'"
          @click="$emit('update:modelValue', !modelValue)">
    <span class="absolute top-0.5 left-0.5 w-3 h-3 bg-surface rounded-full transition-transform duration-300 motion-reduce:transition-none"
          :style="{ transform: modelValue ? 'translateX(16px)' : 'translateX(0)' }"></span>
  </button>
</template>

<script setup>
defineProps({ modelValue: Boolean, disabled: Boolean, label: { type: String, required: true } });
defineEmits(['update:modelValue']);
</script>
