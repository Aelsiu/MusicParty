import test from 'node:test';
import assert from 'node:assert/strict';
import { contrastRatio, deriveCustomPalette, normalizeCustomTheme } from './customTheme.js';

test('custom palette keeps the chosen light or dark foundation', () => {
  const light = deriveCustomPalette({ base: 'light', color: '#2563EB' });
  const dark = deriveCustomPalette({ base: 'dark', color: '#2563EB' });
  assert.equal(light['--medical-50'], '249 250 251');
  assert.equal(light['--surface'], '255 255 255');
  assert.equal(dark['--medical-50'], '15 23 42');
  assert.equal(dark['--surface'], '30 41 59');
  assert.notEqual(light['--medical-200'], deriveCustomPalette({ base: 'light', color: '#F97316' })['--medical-200']);
});

test('generated accents preserve key hue and contrast across extreme choices', () => {
  for (const base of ['light', 'dark']) {
    for (const color of ['#FFFF00', '#FFFFFF', '#000000', '#FF0000', '#2563EB', '#00FFFF']) {
      const palette = deriveCustomPalette({ base, color });
      assert.ok(contrastRatio(palette['--accent'], base === 'dark' ? palette['--medical-50'] : palette['--surface']) >= 4.5);
      for (const value of Object.values(palette)) {
        assert.ok(value.split(' ').every(channel => Number.isInteger(+channel) && +channel >= 0 && +channel <= 255));
      }
    }
  }
  const red = deriveCustomPalette({ base: 'light', color: '#FF0000' })['--accent'].split(' ').map(Number);
  const blue = deriveCustomPalette({ base: 'light', color: '#2563EB' })['--accent'].split(' ').map(Number);
  assert.ok(red[0] > red[2]);
  assert.ok(blue[2] > blue[0]);
});

test('invalid saved theme values fall back to safe defaults', () => {
  assert.deepEqual(normalizeCustomTheme({ base: 'other', color: 'bad' }), { base: 'light', color: '#F97316' });
});
