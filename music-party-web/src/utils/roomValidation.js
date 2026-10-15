const segmenter = new Intl.Segmenter('zh', { granularity: 'grapheme' });
export const graphemes = value => [...segmenter.segment(value)].map(s => s.segment);
export function validRoomName(value) {
    return visibleText(value, 2, 16);
}
export const validLicenseNote = value => value === '' || visibleText(value, 1, 16);
function visibleText(value, min, max) {
    const list = graphemes(value);
    if (!value.trim() || list.length < min || list.length > max) return false;
    return list.every(cluster => {
        const chars = [...cluster];
        const tagFlag = /^\u{1F3F4}[\u{E0061}-\u{E007A}]+\u{E007F}$/u.test(cluster);
        return !/^[\p{M}]+$/u.test(cluster) && chars.every((c, i) => {
            if (/[\p{Cc}\p{Cs}\p{Zl}\p{Zp}]/u.test(c)) return false;
            if (!/\p{Cf}/u.test(c)) return true;
            if (c !== '\u200D') return tagFlag && /[\u{E0061}-\u{E007F}]/u.test(c);
            let before = i - 1;
            while (before >= 0 && /[\p{M}\p{Emoji_Modifier}]/u.test(chars[before])) before--;
            return before >= 0 && i + 1 < chars.length && /\p{Extended_Pictographic}/u.test(chars[before]) && /\p{Extended_Pictographic}/u.test(chars[i + 1]);
        });
    });
}
export const validLicenseKey = value => /^[\x21-\x7E]{8,16}$/.test(value);
export function randomLicenseKey() {
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%&*+-_=';
    let value = '';
    while (value.length < 16) {
        const bytes = crypto.getRandomValues(new Uint8Array(32));
        for (const n of bytes) if (n < Math.floor(256 / alphabet.length) * alphabet.length && value.length < 16) value += alphabet[n % alphabet.length];
    }
    return value;
}
