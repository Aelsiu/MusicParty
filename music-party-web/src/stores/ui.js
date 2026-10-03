// src/stores/ui.js
import { defineStore } from 'pinia';
import { ref, computed, watch } from 'vue';
import { STORAGE_KEYS } from '../constants/keys';
import client from '../api/client';
import { DEFAULT_CUSTOM_THEME, deriveCustomPalette, normalizeCustomTheme } from '../utils/customTheme';
import { clampVolume, readSavedVolume, volumeToGain } from '../utils/volumeMapping';

const themeNames = ['classic', 'night', 'blue', 'night-blue', 'green', 'night-green', 'custom'];
const customThemeKey = 'mp_custom_theme';
let savedCustomTheme = DEFAULT_CUSTOM_THEME;
try {
    savedCustomTheme = normalizeCustomTheme(JSON.parse(localStorage.getItem(customThemeKey)));
} catch { /* 忽略旧浏览器中无效的主题数据 */ }
const savedTheme = localStorage.getItem('mp_theme');
const initialTheme = themeNames.includes(savedTheme) ? savedTheme : 'classic';
const savedLyricPreviewLines = Number(localStorage.getItem(STORAGE_KEYS.LYRIC_PREVIEW_LINES));
const customVariables = Object.keys(deriveCustomPalette(savedCustomTheme));
const writeCustomVariables = (config) => {
    const root = document.documentElement;
    for (const [name, value] of Object.entries(deriveCustomPalette(config))) root.style.setProperty(name, value);
    root.style.colorScheme = config.base;
    root.dataset.customBase = config.base;
};
if (initialTheme === 'custom') writeCustomVariables(savedCustomTheme);
document.documentElement.dataset.theme = initialTheme;

export const useUiStore = defineStore('ui', () => {
    const isLiteMode = ref(false);
    const volume = ref(readSavedVolume(localStorage.getItem(STORAGE_KEYS.VOLUME)));
    const audioVolume = computed(() => volumeToGain(volume.value));
    const autoLiteMode = ref(localStorage.getItem('mp_auto_lite_mode') !== 'false'); // 默认 true
    const lyricPreviewLines = ref([0, 1, 2].includes(savedLyricPreviewLines) ? savedLyricPreviewLines : 0);
    const visualizationEnabled = ref(localStorage.getItem(STORAGE_KEYS.VISUALIZATION) === 'true');
    const authorName = ref('ThorNex X Aelsiu');
    const backWords = ref('MUSIC PARTY');
    const theme = ref(initialTheme);
    const customThemeConfig = ref(savedCustomTheme);

    const applyTheme = (name, customConfig) => {
        if (name === 'custom') {
            writeCustomVariables(customConfig);
            customThemeConfig.value = customConfig;
            localStorage.setItem(customThemeKey, JSON.stringify(customConfig));
        } else {
            for (const variable of customVariables) document.documentElement.style.removeProperty(variable);
            document.documentElement.style.colorScheme = '';
            delete document.documentElement.dataset.customBase;
        }
        theme.value = name;
        document.documentElement.dataset.theme = name;
        localStorage.setItem('mp_theme', name);
        window.dispatchEvent(new Event('musicparty:themechange'));
    };

    const setTheme = async (name, x = window.innerWidth / 2, y = window.innerHeight / 2, customConfig) => {
        if (!themeNames.includes(name)) return;
        const nextCustom = name === 'custom' ? normalizeCustomTheme(customConfig || customThemeConfig.value) : null;
        if (name === theme.value && (name !== 'custom' || JSON.stringify(nextCustom) === JSON.stringify(customThemeConfig.value))) return;
        const switchTheme = () => applyTheme(name, nextCustom);
        if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            switchTheme();
            return;
        }

        const radius = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y));
        if (document.startViewTransition) {
            const transition = document.startViewTransition(switchTheme);
            await transition.ready;
            document.documentElement.animate(
                { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${radius}px at ${x}px ${y}px)`] },
                { duration: 500, easing: 'ease-out', pseudoElement: '::view-transition-new(root)' }
            );
            return;
        }

        const overlay = document.createElement('div');
        const palette = { classic: '249 250 251', night: '15 23 42', blue: '239 246 255', 'night-blue': '15 23 42', green: '242 250 245', 'night-green': '15 23 42' };
        overlay.className = 'theme-ripple';
        overlay.style.setProperty('--theme-ripple-color', name === 'custom' ? deriveCustomPalette(nextCustom)['--medical-50'] : palette[name]);
        overlay.style.setProperty('--theme-ripple-x', `${x}px`);
        overlay.style.setProperty('--theme-ripple-y', `${y}px`);
        document.body.appendChild(overlay);
        requestAnimationFrame(() => { overlay.style.clipPath = `circle(${radius}px at ${x}px ${y}px)`; });
        setTimeout(() => { switchTheme(); overlay.remove(); }, 500);
    };

    const toggleLiteMode = () => {
        isLiteMode.value = !isLiteMode.value;
    };

    const setVolume = (val) => {
        volume.value = clampVolume(val);
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

    // 始终保存实际输出值，兼容旧线性音量记忆，避免重载时再次迁移
    watch(volume, (newVal) => {
        localStorage.setItem(STORAGE_KEYS.VOLUME, String(volumeToGain(newVal)));
    });

    watch(autoLiteMode, (newVal) => {
        localStorage.setItem('mp_auto_lite_mode', newVal.toString());
    });

    watch(lyricPreviewLines, (count) => {
        localStorage.setItem(STORAGE_KEYS.LYRIC_PREVIEW_LINES, String(count));
    });

    watch(visualizationEnabled, (enabled) => {
        localStorage.setItem(STORAGE_KEYS.VISUALIZATION, String(enabled));
    });

    return {
        isLiteMode,
        toggleLiteMode,
        volume,
        audioVolume,
        setVolume,
        lyricPreviewLines,
        setLyricPreviewLines,
        visualizationEnabled,
        autoLiteMode,
        authorName,
        backWords,
        theme,
        setTheme,
        customThemeConfig,
        fetchConfig
    };
});
