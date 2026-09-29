// src/stores/ui.js
import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { STORAGE_KEYS } from '../constants/keys';
import client from '../api/client';

const themeNames = ['classic', 'night', 'blue', 'night-blue'];
const savedTheme = localStorage.getItem('mp_theme');
const initialTheme = themeNames.includes(savedTheme) ? savedTheme : 'classic';
const savedLyricPreviewLines = Number(localStorage.getItem(STORAGE_KEYS.LYRIC_PREVIEW_LINES));
document.documentElement.dataset.theme = initialTheme;

export const useUiStore = defineStore('ui', () => {
    const isLiteMode = ref(false);
    const volume = ref(parseFloat(localStorage.getItem(STORAGE_KEYS.VOLUME) || '0.5'));
    const autoLiteMode = ref(localStorage.getItem('mp_auto_lite_mode') !== 'false'); // 默认 true
    const lyricPreviewLines = ref([0, 1, 2].includes(savedLyricPreviewLines) ? savedLyricPreviewLines : 0);
    const authorName = ref('ThorNex X Aelsiu');
    const backWords = ref('THORNEX');
    const theme = ref(initialTheme);

    const applyTheme = (name) => {
        theme.value = name;
        document.documentElement.dataset.theme = name;
        localStorage.setItem('mp_theme', name);
        window.dispatchEvent(new Event('musicparty:themechange'));
    };

    const setTheme = async (name, x = window.innerWidth / 2, y = window.innerHeight / 2) => {
        if (!themeNames.includes(name) || name === theme.value) return;
        if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            applyTheme(name);
            return;
        }

        const radius = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y));
        if (document.startViewTransition) {
            const transition = document.startViewTransition(() => applyTheme(name));
            await transition.ready;
            document.documentElement.animate(
                { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${radius}px at ${x}px ${y}px)`] },
                { duration: 500, easing: 'ease-out', pseudoElement: '::view-transition-new(root)' }
            );
            return;
        }

        const overlay = document.createElement('div');
        const palette = { classic: '249 250 251', night: '15 23 42', blue: '239 246 255', 'night-blue': '15 23 42' };
        overlay.className = 'theme-ripple';
        overlay.style.setProperty('--theme-ripple-color', palette[name]);
        overlay.style.setProperty('--theme-ripple-x', `${x}px`);
        overlay.style.setProperty('--theme-ripple-y', `${y}px`);
        document.body.appendChild(overlay);
        requestAnimationFrame(() => { overlay.style.clipPath = `circle(${radius}px at ${x}px ${y}px)`; });
        setTimeout(() => { applyTheme(name); overlay.remove(); }, 500);
    };

    const toggleLiteMode = () => {
        isLiteMode.value = !isLiteMode.value;
    };

    const setVolume = (val) => {
        volume.value = Math.max(0, Math.min(1, val));
    };

    const setLyricPreviewLines = (count) => {
        if ([0, 1, 2].includes(count)) lyricPreviewLines.value = count;
    };

    const fetchConfig = async () => {
        try {
            const config = await client.get('/api/config');
            authorName.value = config.authorName;
            backWords.value = config.backWords;
        } catch (e) {
            console.error('Failed to fetch config', e);
        }
    };

    // 监听音量变化并持久化
    watch(volume, (newVal) => {
        localStorage.setItem(STORAGE_KEYS.VOLUME, newVal.toString());
    });

    watch(autoLiteMode, (newVal) => {
        localStorage.setItem('mp_auto_lite_mode', newVal.toString());
    });

    watch(lyricPreviewLines, (count) => {
        localStorage.setItem(STORAGE_KEYS.LYRIC_PREVIEW_LINES, String(count));
    });

    return {
        isLiteMode,
        toggleLiteMode,
        volume,
        setVolume,
        lyricPreviewLines,
        setLyricPreviewLines,
        autoLiteMode,
        authorName,
        backWords,
        theme,
        setTheme,
        fetchConfig
    };
});
