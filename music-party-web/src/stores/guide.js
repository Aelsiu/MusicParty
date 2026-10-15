import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

// This lock belongs to this browser, never to the room playback protocol.
export const useGuideStore = defineStore('guide', () => {
    const scene = ref('idle');
    const active = computed(() => scene.value !== 'idle');
    const main = computed(() => scene.value === 'main');
    const admin = computed(() => scene.value === 'admin');
    function open(next) {
        if (!['intro', 'main', 'admin'].includes(next)) return false;
        scene.value = next;
        return true;
    }
    function close() { scene.value = 'idle'; }
    return { scene, active, main, admin, open, close };
});
