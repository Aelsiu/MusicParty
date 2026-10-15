<template>
  <div class="room-nav-actions flex items-center gap-2 sm:gap-3 shrink-0">
    <button v-if="roomSession.ownerAccess && !user.isGuest" data-guide="mode" type="button" :disabled="!player.synchronized"
            @click="requestMode" class="room-nav-action font-mono text-accent py-2 px-1 disabled:opacity-40"
            :aria-label="player.mediaMode === 'VIDEO' ? '切换到音频模式' : '切换到视频模式'">{{ player.mediaMode === 'VIDEO' ? 'VIDEO ⇄' : 'MUSIC ⇄' }}</button>
    <span v-else data-guide="mode" class="room-nav-action font-mono text-medical-500 py-2 px-1">{{ player.mediaMode === 'VIDEO' ? 'VIDEO' : 'MUSIC' }}</span>
    <button v-if="roomSession.shareEnabled" data-guide="share" type="button" class="room-nav-action font-mono text-accent py-2 px-1"
            :disabled="copying" :aria-busy="copying || sharePhase === 'scrambling' || sharePhase === 'restoring'"
            :aria-label="sharePhase === 'copied' ? '邀请链接已复制，再次点击可重新分享' : '复制房间邀请链接'"
            @click="share" @keydown="ignoreRepeatedKey">
      <span class="room-nav-width" aria-hidden="true">COPIED</span>
      <span class="room-nav-label" aria-hidden="true"><span v-for="(part, index) in shareText" :key="index" :class="{ 'room-nav-dud': part.dud }">{{ part.text }}</span></span>
    </button>
    <button data-guide="return" type="button" class="room-nav-action font-mono text-accent py-2 px-1"
            :aria-busy="returnPhase === 'scrambling' || returnPhase === 'restoring'"
            :aria-label="returnPhase === 'armed' ? '确认退出房间，3 秒内再次点击' : '返回房间入口'"
            @click="returnConfirmation.click" @keydown="ignoreRepeatedKey">
      <span class="room-nav-width" aria-hidden="true">RETURN</span>
      <span class="room-nav-label" aria-hidden="true"><span v-for="(part, index) in returnText" :key="index" :class="{ 'room-nav-dud': part.dud }">{{ part.text }}</span></span>
    </button>
    <span class="sr-only" role="status" aria-live="polite" aria-atomic="true">{{ announcement }}</span>
    <ConfirmDialog :open="!!pendingMode" title="切换房间媒体模式" confirm-label="CONFIRM"
                   :message="`是否切换至${pendingMode?.mode === 'VIDEO' ? '视频模式' : '音频模式'}？`"
                   description="当前模式和目标模式都会暂停并保留现场，等待手动开始"
                   @cancel="pendingMode = null" @confirm="confirmMode" />
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
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import ConfirmDialog from './ConfirmDialog.vue';

const emit = defineEmits(['return']);
const { error } = useToast();
const player = usePlayerStore(), user = useUserStore(), pendingMode = ref(null);
function requestMode() {
  const context = player.playbackContext();
  if (roomSession.ownerAccess && !user.isGuest && context) pendingMode.value = {
    context, generation: player.clientGeneration, roomGeneration: roomSession.generation,
    roomId: roomSession.roomId, mode: player.mediaMode === 'VIDEO' ? 'MUSIC' : 'VIDEO'
  };
}
function confirmMode() {
  const pending = pendingMode.value; pendingMode.value = null;
  if (!pending || !roomSession.ownerAccess || user.isGuest || pending.generation !== player.clientGeneration
      || pending.roomGeneration !== roomSession.generation || pending.roomId !== roomSession.roomId
      || !player.isContextCurrent(pending.context)) return;
  player.switchMediaMode(pending.mode, pending.context);
}
watch(() => [player.clientGeneration, player.playbackEpoch, player.modeEpoch, player.synchronized,
    roomSession.generation, roomSession.roomId, roomSession.ownerAccess, user.isGuest], () => {
  if (pendingMode.value && (!roomSession.ownerAccess || user.isGuest || !player.isContextCurrent(pendingMode.value.context)
      || pendingMode.value.generation !== player.clientGeneration || pendingMode.value.roomGeneration !== roomSession.generation
      || pendingMode.value.roomId !== roomSession.roomId)) pendingMode.value = null;
}, { flush: 'sync' });
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
