import { match } from 'pinyin-pro';

export const matchesPlaylistSong = (song, keyword) => {
    const query = keyword.trim().toLowerCase();
    if (!query) return true;
    return [song.name, ...(song.artists || [])].some((value) => {
        const text = String(value || '');
        return text.toLowerCase().includes(query) || Array.isArray(match(text, query));
    });
};
