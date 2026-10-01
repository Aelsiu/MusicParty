<template>
  <ConfirmDialog :open="Boolean(cleanup.pending)" title="数据清理 / DATA_CLEANUP"
    :message="cleanup.pending?.message || ''" confirm-label="确认清理"
    :busy="cleanup.busy" :error-message="cleanup.error" @cancel="cleanup.cancel" @confirm="cleanup.confirm" />
</template>
<script setup>
import ConfirmDialog from './ConfirmDialog.vue';
import { useCleanupStore } from '../stores/cleanup';
const cleanup = useCleanupStore();
</script>
