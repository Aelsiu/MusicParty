import test from 'node:test';
import assert from 'node:assert/strict';
import { validLicenseNote, validRoomName } from './roomValidation.js';

test('license notes allow empty or one to sixteen visible graphemes', () => {
    for (const value of ['', '一', '中文🎵', '👨‍👩‍👧‍👦'.repeat(16), '🇨🇳'.repeat(16)]) assert.equal(validLicenseNote(value),true,value);
    for (const value of [' '.repeat(2), '\n', 'a\nb', 'a\u200bb', '🎵'.repeat(17), '\u0301', 'a\u200Db']) assert.equal(validLicenseNote(value),false,value);
    assert.equal(validRoomName('🎵'),false);
    assert.equal(validRoomName('🎵🎵'),true);
});
