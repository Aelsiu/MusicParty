<template>
  <Teleport to="body">
    <Transition
        enter-active-class="transition-opacity duration-200 ease-out"
        enter-from-class="opacity-0"
        enter-to-class="opacity-100"
        leave-active-class="transition-opacity duration-150 ease-in"
        leave-from-class="opacity-100"
        leave-to-class="opacity-0"
    >
      <div v-if="open" class="fixed inset-0 z-[160] flex items-center justify-center p-4 bg-overlay/60 backdrop-blur-sm text-medical-900 font-sans" @click.self="cancel">
        <section
            ref="dialogRef"
            role="alertdialog"
            aria-modal="true"
            :aria-labelledby="titleId"
            :aria-describedby="messageId"
            :aria-busy="busy"
            tabindex="-1"
            class="w-full max-w-sm bg-surface border border-medical-200 shadow-2xl chamfer-br overflow-hidden outline-none"
        >
          <header class="p-4 bg-medical-50 border-b border-medical-200 flex items-center justify-between gap-4">
            <h3 :id="titleId" class="font-mono text-sm font-bold flex items-center gap-2">
              <span class="w-1 h-4 bg-accent shrink-0" aria-hidden="true"></span>
              {{ title }}
            </h3>
            <button type="button" :disabled="busy" @click="cancel" aria-label="关闭确认弹窗" class="text-medical-400 hover:text-accent focus-visible:text-accent disabled:opacity-40 transition-colors">
              <X class="w-4 h-4" />
            </button>
          </header>
          <div class="p-6">
            <p :id="messageId" class="text-sm leading-relaxed whitespace-pre-line break-words">{{ message }}</p>
            <p v-if="errorMessage" role="alert" class="mt-3 text-xs text-red-500 leading-relaxed">{{ errorMessage }}</p>
            <div class="flex gap-3 mt-6">
              <button ref="cancelRef" type="button" :disabled="busy" @click="cancel" class="flex-1 border border-medical-200 py-3 text-xs font-bold font-mono hover:border-accent hover:text-accent focus-visible:border-accent focus-visible:text-accent disabled:opacity-50 transition-colors">
                CANCEL
              </button>
              <button type="button" :disabled="busy" @click="confirm" class="flex-1 bg-accent text-white py-3 text-xs font-bold flex items-center justify-center gap-2 hover:brightness-110 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:opacity-50 transition-all">
                <Loader2 v-if="busy" class="w-4 h-4 animate-spin" aria-hidden="true" />
                {{ busy ? '正在清理...' : confirmLabel }}
              </button>
            </div>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue';
import { Loader2, X } from 'lucide-vue-next';
import { registerBackHandler } from '../services/backNavigation';

const props = defineProps({
  open: Boolean,
  title: { type: String, default: '确认操作' },
  message: { type: String, default: '' },
  confirmLabel: { type: String, default: '确认' },
  busy: Boolean,
  errorMessage: { type: String, default: '' },
});
const emit = defineEmits(['confirm', 'cancel']);
const titleId = useId();
const messageId = useId();
const dialogRef = ref(null);
const cancelRef = ref(null);
let previousFocus = null;
let focusVersion = 0;

function cancel() {
  if (!props.busy) emit('cancel');
}

function confirm() {
  if (!props.busy) emit('confirm');
}

function handleKeydown(event) {
  if (!props.open) return;
  if (event.key === 'Escape') {
    event.preventDefault();
    event.stopImmediatePropagation();
    cancel();
    return;
  }
  if (event.key === 'Enter' && event.target?.tagName !== 'BUTTON') {
    event.preventDefault();
    return;
  }
  if (event.key !== 'Tab') return;

  const buttons = [...(dialogRef.value?.querySelectorAll('button:not(:disabled)') || [])];
  const first = buttons[0];
  const last = buttons[buttons.length - 1];
  if (!first) {
    event.preventDefault();
    dialogRef.value?.focus();
  } else if (event.shiftKey && (document.activeElement === first || !buttons.includes(document.activeElement))) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && (document.activeElement === last || !buttons.includes(document.activeElement))) {
    event.preventDefault();
    first.focus();
  }
}

function restoreFocus() {
  if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true });
  previousFocus = null;
}

watch(() => props.open, async (open) => {
  const current = ++focusVersion;
  if (open) {
    previousFocus = document.activeElement;
    document.addEventListener('keydown', handleKeydown, true);
    await nextTick();
    if (props.open && current === focusVersion) {
      (props.busy ? dialogRef.value : cancelRef.value)?.focus({ preventScroll: true });
    }
  } else {
    document.removeEventListener('keydown', handleKeydown, true);
    restoreFocus();
  }
}, { immediate: true });

const unregisterBack = registerBackHandler(160, () => {
  if (!props.open) return false;
  cancel();
  return true;
});

onBeforeUnmount(() => {
  focusVersion++;
  document.removeEventListener('keydown', handleKeydown, true);
  unregisterBack();
  restoreFocus();
});
</script>
