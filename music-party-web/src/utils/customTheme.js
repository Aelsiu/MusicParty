const LIGHT = {
  medical: ['249 250 251', '243 244 246', '229 231 235', '209 213 219', '156 163 175', '107 114 128', '75 85 99', '55 65 81', '31 41 55', '17 24 39'],
  surface: '255 255 255', strong: '17 24 39', overlay: '17 24 39'
};
const DARK = {
  medical: ['15 23 42', '23 32 51', '51 65 85', '71 85 105', '148 163 184', '164 178 195', '192 203 216', '208 218 228', '226 232 240', '248 250 252'],
  surface: '30 41 59', strong: '51 65 85', overlay: '15 23 42'
};
const STEPS = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900];
const TINT = [0, .06, .1, .09, .07, .055, .04, .025, .015, 0];
export const DEFAULT_CUSTOM_THEME = { base: 'light', color: '#F97316' };

export function normalizeCustomTheme(value) {
  return {
    base: value?.base === 'dark' ? 'dark' : 'light',
    color: /^#[0-9a-f]{6}$/i.test(value?.color || '') ? value.color.toUpperCase() : DEFAULT_CUSTOM_THEME.color
  };
}

const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
const channels = value => value.startsWith('#')
  ? [1, 3, 5].map(index => parseInt(value.slice(index, index + 2), 16))
  : value.split(' ').map(Number);
const serialize = values => values.map(value => Math.round(clamp(value, 0, 255))).join(' ');
const linear = value => {
  const c = value / 255;
  return c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4;
};
const srgb = value => (value <= .0031308 ? 12.92 * value : 1.055 * value ** (1 / 2.4) - .055) * 255;

// 在 OKLCH 中保持色相；越界时收缩色度，避免高饱和颜色偏色。
function toOklch(color) {
  const [r, g, b] = channels(color).map(linear);
  const l = Math.cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b);
  const m = Math.cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b);
  const s = Math.cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b);
  const a = 1.9779984951 * l - 2.428592205 * m + .4505937099 * s;
  const blue = .0259040371 * l + .7827717662 * m - .808675766 * s;
  return { l: .2104542553 * l + .793617785 * m - .0040720468 * s, c: Math.hypot(a, blue), h: Math.atan2(blue, a) };
}

function fromOklch(lightness, chroma, hue) {
  const a = chroma * Math.cos(hue);
  const b = chroma * Math.sin(hue);
  const l = (lightness + .3963377774 * a + .2158037573 * b) ** 3;
  const m = (lightness - .1055613458 * a - .0638541728 * b) ** 3;
  const s = (lightness - .0894841775 * a - 1.291485548 * b) ** 3;
  const values = [
    4.0767416621 * l - 3.3077115913 * m + .2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - .3413193965 * s,
    -.0041960863 * l - .7034186147 * m + 1.707614701 * s
  ];
  return values.every(value => value >= 0 && value <= 1) ? values.map(srgb) : null;
}

function gamutColor(lightness, chroma, hue) {
  let low = 0;
  let high = chroma;
  for (let i = 0; i < 16; i++) {
    const middle = (low + high) / 2;
    if (fromOklch(lightness, middle, hue)) low = middle;
    else high = middle;
  }
  return (fromOklch(lightness, low, hue) || fromOklch(lightness, 0, hue)).map(Math.round);
}

export function contrastRatio(first, second) {
  const luminance = value => {
    const [r, g, b] = channels(value).map(linear);
    return .2126 * r + .7152 * g + .0722 * b;
  };
  const a = luminance(first);
  const b = luminance(second);
  return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
}

export function deriveCustomPalette(input) {
  const { base, color } = normalizeCustomTheme(input);
  const foundation = base === 'dark' ? DARK : LIGHT;
  const key = toOklch(color);
  const chroma = key.c < .02 ? 0 : clamp(key.c, .035, .18);
  let lightness = clamp(key.l, base === 'dark' ? .56 : .36, base === 'dark' ? .75 : .62);
  const against = base === 'dark' ? foundation.medical[0] : foundation.surface;
  let accent = gamutColor(lightness, chroma, key.h);
  while (contrastRatio(serialize(accent), against) < 4.5 && lightness > .2 && lightness < .85) {
    lightness += base === 'dark' ? .01 : -.01;
    accent = gamutColor(lightness, chroma, key.h);
  }
  const hover = gamutColor(clamp(lightness + (base === 'dark' ? .06 : -.06), .2, .85), chroma, key.h);
  const palette = {
    '--surface': foundation.surface,
    '--strong': foundation.strong,
    '--overlay': foundation.overlay,
    '--accent': serialize(accent),
    '--accent-hover': serialize(hover)
  };
  STEPS.forEach((step, index) => {
    const source = channels(foundation.medical[index]);
    palette[`--medical-${step}`] = serialize(source.map((value, channel) => value * (1 - TINT[index]) + accent[channel] * TINT[index]));
  });
  return palette;
}
