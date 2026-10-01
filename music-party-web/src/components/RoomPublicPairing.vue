<template>
  <PairingCode v-if="display.open" :code="display.code" :next-update-at="display.nextUpdateAt"
               :interval-minutes="display.intervalMinutes" :clock-offset="display.offset"
               inline copy-label="复制房间配对码" data-room-pairing />
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { useEventListener } from '@vueuse/core';
import PairingCode from './PairingCode.vue';
import { roomsApi } from '../api/rooms';
import { roomSession } from '../services/roomSession';
import { usePlayerStore } from '../stores/player';
import { createPublicPairingSession } from '../services/publicPairing';

const player = usePlayerStore();
const display = ref({ open: false });
const session = createPublicPairingSession({ fetchPairing: id => roomsApi.pairing(id), publish: value => { display.value = value; } });
watch(() => [roomSession.roomId, player.connected, roomSession.ownerAccess,
  roomSession.ownerAccess ? roomSession.managerToken : roomSession.roomToken],
  ([id, connected]) => { if (id && connected) session.start(id); else session.stop(); }, { immediate: true, flush: 'sync' });
useEventListener(window, 'musicparty:pairing', event => session.changed(event.detail));
useEventListener(document, 'visibilitychange', () => { if (!document.hidden) session.refresh(); });
let timer;
onMounted(() => { timer = setInterval(() => session.tick(), 1000); });
onBeforeUnmount(() => { session.stop(); clearInterval(timer); });
</script>
