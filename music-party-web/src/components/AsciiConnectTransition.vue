<template>
  <canvas ref="canvas" class="fixed inset-0 z-[9000] w-screen h-screen pointer-events-auto" aria-hidden="true"></canvas>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';

const props = defineProps({ sourceRects: { type: Array, required: true } });
const emit = defineEmits(['finished']);
const canvas = ref(null);
let frame = 0;
let resizeObserver;
let startedAt = 0;
let source = [];
let target = [];

const cellWidth = 11;
const cellHeight = 16;
const hash = (x, y) => ((x * 73 + y * 151 + x * y * 19) % 997) / 997;
const point = (x, y, char) => ({ x, y, char, order: hash(x, y) });

function box(rect, inset = 0) {
  if (!rect || rect.width < 12 || rect.height < 12) return [];
  const left = Math.round((rect.left + inset) / cellWidth);
  const right = Math.round((rect.right - inset) / cellWidth);
  const top = Math.round((rect.top + inset) / cellHeight);
  const bottom = Math.round((rect.bottom - inset) / cellHeight);
  const cells = [];
  for (let x = left; x <= right; x++) {
    cells.push(point(x, top, x === left || x === right ? '+' : '-'));
    cells.push(point(x, bottom, x === left || x === right ? '+' : '-'));
  }
  for (let y = top + 1; y < bottom; y++) {
    cells.push(point(left, y, '|'), point(right, y, '|'));
  }
  return cells;
}

function label(rect, value, row = .5) {
  if (!rect) return [];
  const center = Math.round((rect.left + rect.width / 2) / cellWidth);
  const y = Math.round((rect.top + rect.height * row) / cellHeight);
  return [...value].map((char, index) => point(center - Math.floor(value.length / 2) + index, y, char));
}

function listRows(rect) {
  if (!rect || rect.width < 40) return [];
  return [.17, .27, .37, .47, .57, .67].flatMap(row => label(rect, '[==]  ----------------', row));
}

function coverTexture(rect) {
  if (!rect) return [];
  const cells = [];
  const left = Math.ceil(rect.left / cellWidth) + 2;
  const right = Math.floor(rect.right / cellWidth) - 2;
  const top = Math.ceil(rect.top / cellHeight) + 2;
  const bottom = Math.floor(rect.bottom / cellHeight) - 2;
  for (let y = top; y < bottom; y++) {
    for (let x = left; x < right; x++) {
      if (hash(x, y) > .65) cells.push(point(x, y, hash(x + 1, y) > .5 ? '#' : '.'));
    }
  }
  return cells;
}

function buildSource() {
  const [title, room, status, button] = props.sourceRects;
  if (!title || !button) return [];
  const card = {
    left: Math.min(title.left, button.left) - 28,
    right: Math.max(title.right, button.right) + 28,
    top: title.top - 24,
    bottom: button.bottom + 24
  };
  card.width = card.right - card.left;
  card.height = card.bottom - card.top;
  return [
    ...box(card),
    ...label(title, 'MUSIC PARTY'),
    ...label(room, 'ROOM ONLINE'),
    ...label(status, 'SYSTEM READY'),
    ...box(button),
    ...label(button, 'CONNECT')
  ];
}

function buildTarget() {
  const root = document.querySelector('[data-ascii-room]');
  if (!root) return [];
  const rect = selector => root.querySelector(selector)?.getBoundingClientRect();
  const header = rect('header');
  const center = rect('main');
  const sides = [...root.querySelectorAll('aside')].map(node => node.getBoundingClientRect()).filter(item => item.width && item.height);
  const player = root.querySelector('#tutorial-source')?.parentElement?.getBoundingClientRect();
  const cover = rect('#tutorial-like');
  return [
    ...box(header), ...label(header, 'MUSIC PARTY // CONNECTED'),
    ...sides.flatMap((side, index) => [...box(side), ...label(side, index ? 'QUEUE' : 'USERS', .08), ...listRows(side)]),
    ...box(center, 12), ...label(center, 'PLAYBACK', .1),
    ...box(cover), ...coverTexture(cover), ...label(cover, 'NOW PLAYING'),
    ...box(player), ...label(player, 'PLAYER CONTROL')
  ];
}

function draw(timestamp) {
  const element = canvas.value;
  if (!element) return;
  if (!startedAt) startedAt = timestamp;
  const elapsed = Math.min(timestamp - startedAt, 1000);
  const context = element.getContext('2d');
  const styles = getComputedStyle(document.documentElement);
  const reveal = elapsed > 850 ? (1000 - elapsed) / 150 : 1;
  context.clearRect(0, 0, window.innerWidth, window.innerHeight);
  context.fillStyle = `rgba(${styles.getPropertyValue('--medical-50').trim().split(/\s+/).join(',')}, ${reveal})`;
  context.fillRect(0, 0, window.innerWidth, window.innerHeight);
  context.font = 'bold 12px monospace';
  context.textBaseline = 'middle';
  const accent = styles.getPropertyValue('--accent').trim().split(/\s+/).join(',');
  if (elapsed < 480) {
    const progress = elapsed / 480;
    for (const glyph of source) {
      if (glyph.order < progress) continue;
      context.fillStyle = `rgba(${accent}, ${1 - progress * .45})`;
      const scatter = progress * progress * 28;
      context.fillText(glyph.char, glyph.x * cellWidth + (glyph.order - .5) * scatter, glyph.y * cellHeight + (glyph.order - .5) * scatter);
    }
  } else {
    const progress = (elapsed - 480) / 520;
    for (const glyph of target) {
      if (glyph.order > Math.min(progress * 1.35, 1)) continue;
      context.fillStyle = `rgba(${accent}, ${reveal})`;
      context.fillText(glyph.char, glyph.x * cellWidth, glyph.y * cellHeight);
    }
  }
  if (elapsed < 1000) frame = requestAnimationFrame(draw);
  else emit('finished');
}

function resize() {
  if (!canvas.value) return;
  const ratio = Math.min(window.devicePixelRatio || 1, 2);
  canvas.value.width = Math.round(window.innerWidth * ratio);
  canvas.value.height = Math.round(window.innerHeight * ratio);
  canvas.value.getContext('2d').setTransform(ratio, 0, 0, ratio, 0, 0);
  target = buildTarget();
}

onMounted(async () => {
  await nextTick();
  source = buildSource();
  resize();
  resizeObserver = new ResizeObserver(resize);
  resizeObserver.observe(document.documentElement);
  draw(performance.now());
});

onBeforeUnmount(() => {
  cancelAnimationFrame(frame);
  resizeObserver?.disconnect();
});
</script>
