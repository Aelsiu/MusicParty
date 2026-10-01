<template>
  <div v-if="open" class="fixed inset-0 z-[115] bg-overlay/60 backdrop-blur-sm flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-labelledby="pairing-modal-title" @click.self="close">
    <section class="w-full max-w-sm bg-surface border border-medical-200 shadow-2xl chamfer-br">
      <header class="p-4 bg-medical-50 border-b border-medical-200 flex items-center justify-between gap-3">
        <h2 id="pairing-modal-title" class="font-mono font-bold text-sm flex items-center gap-2"><KeyRound class="w-4 h-4 text-accent" /> PAIRING CODE</h2>
        <button @click="close" class="p-2 text-medical-400 hover:text-accent" aria-label="关闭配对码"><X class="w-4 h-4" /></button>
      </header>
      <div class="p-6">
        <p class="font-bold text-medical-900 break-words mb-5">{{ roomSession.roomName }}</p>
        <RoomPairingCard />
      </div>
    </section>
  </div>
</template>
<script setup>
import { onBeforeUnmount } from 'vue';
import { KeyRound, X } from 'lucide-vue-next';
import RoomPairingCard from './RoomPairingCard.vue';
import { roomSession } from '../services/roomSession';
import { registerBackHandler } from '../services/backNavigation';
const props = defineProps({ open: Boolean });
const emit = defineEmits(['close']);
const close = () => emit('close');
const unregisterBack = registerBackHandler(115, () => {
  if (!props.open) return false;
  close();
  return true;
});
onBeforeUnmount(unregisterBack);
</script>
