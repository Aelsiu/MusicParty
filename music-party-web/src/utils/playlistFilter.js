import { match } from 'pinyin-pro';
import { toRomaji } from 'wanakana';

const kanaPattern = /[\u3040-\u30ff]/u;
const romajiOptions = {
    customRomajiMapping: {
        'てぃ': 'ti', 'でぃ': 'di', 'とぅ': 'tu', 'どぅ': 'du',
        'ふぁ': 'fa', 'ふぃ': 'fi', 'ふぇ': 'fe', 'ふぉ': 'fo',
        'うぃ': 'wi', 'うぇ': 'we', 'うぉ': 'wo',
        'ゔぁ': 'va', 'ゔぃ': 'vi', 'ゔぇ': 've', 'ゔぉ': 'vo'
    }
};

export const matchesPlaylistSong = (song, keyword) => {
    const query = keyword.trim().toLowerCase();
    if (!query) return true;
    return [song.name, ...(song.artists || [])].some((value) => {
        const text = String(value || '');
        if (text.toLowerCase().includes(query) || Array.isArray(match(text, query))) return true;
        if (!kanaPattern.test(text)) return false;
        const romaji = toRomaji(text, romajiOptions).toLowerCase();
        return romaji.includes(query) || romaji.replace(/([aeiou])-/g, '$1$1').includes(query);
    });
};
