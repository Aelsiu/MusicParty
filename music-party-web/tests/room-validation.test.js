import assert from 'node:assert/strict';
import { test } from 'node:test';
import { validRoomName, validLicenseKey, graphemes, randomLicenseKey } from '../src/utils/roomValidation.js';

test('room names count composed emoji as visible characters', () => {
    for (const name of ['中文', '🌙🎵', '🇨🇳🇨🇦', '👨‍👩‍👧‍👦👩🏽‍💻', '🎵'.repeat(16)]) assert.ok(validRoomName(name), name);
    assert.equal(graphemes('👨‍👩‍👧‍👦👩🏽‍💻').length, 2);
    for (const name of ['🎵', '🎵'.repeat(17), '  ', '\u00a0\u00a0', 'a\nb', 'a\u200bb', 'a\u200db', '😀\u200da', '😀\u{E0061}b', '\u0301\u0302']) assert.equal(validRoomName(name), false, name);
});

test('license input and random generation obey the same printable ASCII policy', () => {
    for (const key of ['OwnerKey9', 'a!#$%&*?']) assert.ok(validLicenseKey(key));
    for (const key of ['short', '1234567 ', '中文12345678', 'a'.repeat(17), '1234567\n']) assert.equal(validLicenseKey(key), false);
    for (let i = 0; i < 50; i++) { const key = randomLicenseKey(); assert.equal(key.length, 16); assert.ok(validLicenseKey(key)); }
});
