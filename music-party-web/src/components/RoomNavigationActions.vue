<template>
  <div class="room-nav-actions flex items-center gap-2 sm:gap-3 shrink-0">
    <button v-if="roomSession.shareEnabled" type="button" class="room-nav-action font-mono text-accent py-2 px-1"
            :disabled="copying" :aria-busy="copying || sharePhase === 'scrambling' || sharePhase === 'restoring'"
            :aria-label="sharePhase === 'copied' ? '邀请链接已复制，再次点击可重新分享' : '复制房间邀请链接'"
            @click="share" @keydown="ignoreRepeatedKey">
      <span class="room-nav-width" aria-hidden="true">COPIED</span>
      <span class="room-nav-label" aria-hidden="true"><span v-for="(part, index) in shareText" :key="index" :class="{ 'room-nav-dud': part.dud }">{{ part.text }}</span></span>
    </button>
    <button type="button" class="room-nav-action font-mono text-accent py-2 px-1"
            :aria-busy="returnPhase === 'scrambling' || returnPhase === 'restoring'"
            :aria-label="returnPhase === 'armed' ? '确认退出房间，3 秒内再次点击' : '返回房间入口'"
            @click="returnConfirmation.click" @keydown="ignoreRepeatedKey">
      <span class="room-nav-width" aria-hidden="true">RETURN</span>
      <span class="room-nav-label" aria-hidden="true"><span v-for="(part, index) in returnText" :key="index" :class="{ 'room-nav-dud': part.dud }">{{ part.text }}</span></span>
    </button>
    <span class="sr-only" role="status" aria-live="polite" aria-atomic="true">{{ announcement }}</span>
  </div>
</template>

<script setup>
import { ref, watch, onBeforeUnmount } from 'vue';
import { useEventListener } from '@vueuse/core';
import { roomSession } from '../services/roomSession';
import { roomsApi } from '../api/rooms';
import { copyRoomInvite } from '../services/shareInvite';
import { createTextScrambler } from '../utils/textScramble';
import { createReturnConfirmation } from '../services/returnConfirmation';
import { useToast } from '../composables/useToast';

const emit = defineEmits(['return']);
const { error } = useToast();
const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const shareText = ref([{ text: 'SHARE', dud: false }]), returnText = ref([{ text: 'RETURN', dud: false }]);
const sharePhase = ref('idle'), returnPhase = ref('idle'), copying = ref(false), announcement = ref('');
const shareAnimation = createTextScrambler({ publish: value => { shareText.value = value; }, reducedMotion });
const returnAnimation = createTextScrambler({ publish: value => { returnText.value = value; }, reducedMotion });
let alive = true, copyGeneration = 0, shareTimer;
const returnConfirmation = createReturnConfirmation({
  animation: returnAnimation, confirm: () => emit('return'),
  publish: value => {
    returnPhase.value = value;
    if (value === 'armed') announcement.value = '3 秒内再次点击即可退出房间';
    else if (value === 'restoring') announcement.value = '退出确认已取消';
  }
});
function resetShare() {
  ++copyGeneration; copying.value = false; clearTimeout(shareTimer);
  sharePhase.value = 'idle'; shareAnimation.reset('SHARE');
}
function reset() { resetShare(); returnConfirmation.reset(); announcement.value = ''; }
function ignoreRepeatedKey(event) {
  if (event.repeat && (event.key === 'Enter' || event.key === ' ')) event.preventDefault();
}
function displayCopied(isCurrent) {
  clearTimeout(shareTimer); sharePhase.value = 'scrambling';
  shareAnimation.animate('SHARE', 'COPIED', 280, () => {
    if (!isCurrent()) return;
    sharePhase.value = 'copied'; announcement.value = '邀请链接已复制';
    shareTimer = setTimeout(() => {
      if (!isCurrent()) return;
      sharePhase.value = 'restoring';
      shareAnimation.animate('COPIED', 'SHARE', 220, () => { if (isCurrent()) { sharePhase.value = 'idle'; announcement.value = ''; } });
    }, 1200);
  });
}
async function share() {
  if (copying.value || !roomSession.shareEnabled || !['idle', 'copied'].includes(sharePhase.value)) return;
  const current = ++copyGeneration, id = roomSession.roomId, session = roomSession.generation, revision = roomSession.shareRevision;
  const isCurrent = () => alive && current === copyGeneration && roomSession.roomId === id
    && roomSession.generation === session && roomSession.shareRevision === revision && roomSession.shareEnabled;
  copying.value = true;
  let notice = '';
  const copied = await copyRoomInvite({ roomId: id, origin: window.location.origin, invite: roomsApi.invite,
    isCurrent, notify: value => { notice = value; } });
  if (!isCurrent()) return;
  copying.value = false;
  if (!copied) { if (notice) error(notice); return; }
  displayCopied(isCurrent);
}
watch(() => [roomSession.roomId, roomSession.generation], reset, { flush: 'sync' });
watch(() => roomSession.shareRevision, resetShare, { flush: 'sync' });
useEventListener(window, 'musicparty:invite-copied', event => {
  const detail = event.detail;
  if (!detail || !roomSession.shareEnabled || detail.roomId !== roomSession.roomId
      || detail.generation !== roomSession.generation || detail.shareRevision !== roomSession.shareRevision) return;
  resetShare();
  const current = copyGeneration;
  displayCopied(() => alive && current === copyGeneration && roomSession.shareEnabled
    && detail.roomId === roomSession.roomId && detail.generation === roomSession.generation
    && detail.shareRevision === roomSession.shareRevision);
});
useEventListener(document, 'visibilitychange', () => { if (document.hidden) returnConfirmation.reset(); });
onBeforeUnmount(() => { alive = false; reset(); });
</script>

<style scoped>
.room-nav-action{display:inline-grid;place-items:center;position:relative;white-space:nowrap;font-size:inherit;line-height:inherit;border-radius:0;flex-shrink:0}
.room-nav-width,.room-nav-label{grid-area:1/1;white-space:pre}
.room-nav-width{visibility:hidden}
.room-nav-dud{opacity:.48}
.room-nav-action:disabled{cursor:progress}
@media(pointer:coarse){.room-nav-action{min-width:44px;min-height:44px}}
</style>
