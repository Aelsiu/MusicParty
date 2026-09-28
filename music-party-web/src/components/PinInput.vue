<template>
  <div class="relative grid grid-cols-4 gap-2 w-full max-w-64 mx-auto cursor-text focus-within:outline focus-within:outline-2 focus-within:outline-accent">
    <span v-for="index in 4" :key="index" aria-hidden="true"
          class="h-12 flex items-center justify-center border border-medical-300 bg-medical-50 text-medical-900 text-xl font-bold font-mono">
      {{ modelValue.length >= index ? '●' : '' }}
    </span>
    <input :value="modelValue" @input="onInput" @keydown.enter="$emit('complete')"
           type="password" inputmode="numeric" pattern="[0-9]*" maxlength="4"
           autocomplete="off" :aria-label="label"
           class="absolute inset-0 w-full h-full opacity-0 cursor-text" />
  </div>
</template>

<script setup>
defineProps({ modelValue: { type: String, default: '' }, label: { type: String, default: '四位房间密码' } });
const emit = defineEmits(['update:modelValue', 'complete']);

const onInput = (event) => {
  const value = event.target.value.replace(/[^0-9]/g, '').slice(0, 4);
  event.target.value = value;
  emit('update:modelValue', value);
};
</script>
