<template>
  <div class="relative grid grid-cols-4 gap-2 w-full max-w-64 mx-auto cursor-text">
    <span v-for="index in 4" :key="index" aria-hidden="true"
          class="h-12 flex items-center justify-center border bg-medical-50 text-medical-900 text-2xl font-bold font-mono transition-colors"
          :class="isComplete || (focused && index === activeIndex) ? 'border-accent ring-1 ring-accent' : 'border-medical-300'">
      {{ modelValue[index - 1] || '' }}
    </span>
    <input :value="modelValue" @input="onInput" @keydown.enter="$emit('complete')"
           @focus="focused = true; updateCaret($event)" @blur="focused = false"
           @click="updateCaret" @keyup="updateCaret" @select="updateCaret"
           type="text" inputmode="numeric" pattern="[0-9]*" maxlength="4"
           autocomplete="off" :aria-label="label"
           class="absolute inset-0 w-full h-full opacity-0 cursor-text" />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';

const props = defineProps({ modelValue: { type: String, default: '' }, label: { type: String, default: '四位配对码' } });
const emit = defineEmits(['update:modelValue', 'complete']);
const focused = ref(false);
const caret = ref(0);
const activeIndex = computed(() => Math.min(caret.value, props.modelValue.length, 3) + 1);
const isComplete = computed(() => /^[0-9]{4}$/.test(props.modelValue));
const updateCaret = (event) => { caret.value = event.target.selectionStart ?? props.modelValue.length; };

const onInput = (event) => {
  const position = event.target.value.slice(0, event.target.selectionStart).replace(/[^0-9]/g, '').length;
  const value = event.target.value.replace(/[^0-9]/g, '').slice(0, 4);
  event.target.value = value;
  event.target.setSelectionRange(position, position);
  updateCaret(event);
  emit('update:modelValue', value);
};
</script>
