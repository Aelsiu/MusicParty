<template>
  <canvas ref="canvas" class="fixed inset-0 z-[200] w-screen h-screen pointer-events-auto" aria-hidden="true"></canvas>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue';

const props = defineProps({ x: Number, y: Number });
const emit = defineEmits(['dissolve', 'finished']);
const canvas = ref(null);
const glyphs = ' .:-=+*#%@';
const glitch = '!/\\|<>[]{}01#%';
const cellWidth = 9;
const cellHeight = 15;
const hold = 130;
const duration = 1000;
let frame = 0;
let timer = 0;
let finished = false;

const luminance = ([r, g, b]) => (.2126 * r + .7152 * g + .0722 * b) / 255;
const contrast = (a, b) => {
  const first = luminance(a);
  const second = luminance(b);
  return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
};
const mix = (a, b, amount) => a.map((value, index) => Math.round(value * (1 - amount) + b[index] * amount));
const rgb = values => `rgb(${values.join(',')})`;

function themeInk(color, accent) {
  if (contrast(color, accent) >= 3) return rgb(accent);
  const opposite = luminance(color) > .5 ? [0, 0, 0] : [255, 255, 255];
  for (let amount = .2; amount <= 1; amount += .2) {
    const candidate = mix(accent, opposite, amount);
    if (contrast(color, candidate) >= 3) return rgb(candidate);
  }
  return rgb(opposite);
}

function styledClone(node) {
  if (node.nodeType !== Node.ELEMENT_NODE) return node.cloneNode(true);
  const clone = node.cloneNode(false);
  const style = getComputedStyle(node);
  for (let index = 0; index < style.length; index++) {
    const property = style.item(index);
    clone.style.setProperty(property, style.getPropertyValue(property));
  }
  clone.style.animation = 'none';
  clone.style.transition = 'none';
  for (const child of node.childNodes) clone.appendChild(styledClone(child));
  return clone;
}

async function captureScreen(screen, width, height) {
  const clone = styledClone(screen);
  clone.style.position = 'relative';
  clone.style.inset = 'auto';
  clone.style.width = `${width}px`;
  clone.style.height = `${height}px`;
  const content = new XMLSerializer().serializeToString(clone);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><foreignObject width="100%" height="100%">${content}</foreignObject></svg>`;
  const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
  try {
    const image = new Image();
    await new Promise((resolve, reject) => {
      const timeout = window.setTimeout(() => reject(new Error('SVG capture timed out')), 110);
      image.onload = () => { clearTimeout(timeout); resolve(); };
      image.onerror = () => { clearTimeout(timeout); reject(new Error('SVG capture failed')); };
      image.src = url;
    });
    const result = document.createElement('canvas');
    result.width = width;
    result.height = height;
    result.getContext('2d').drawImage(image, 0, 0);
    return result;
  } finally {
    URL.revokeObjectURL(url);
  }
}

function captureFallback(screen, width, height) {
  const result = document.createElement('canvas');
  result.width = width;
  result.height = height;
  const context = result.getContext('2d');
  context.fillStyle = getComputedStyle(screen).backgroundColor;
  context.fillRect(0, 0, width, height);
  for (const element of screen.children) {
    const rect = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    if (style.backgroundColor !== 'rgba(0, 0, 0, 0)') {
      context.fillStyle = style.backgroundColor;
      context.fillRect(rect.left, rect.top, rect.width, rect.height);
    }
    context.fillStyle = style.color;
    context.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
    context.textAlign = 'center';
    context.textBaseline = 'middle';
    context.fillText(element.textContent.trim(), rect.left + rect.width / 2, rect.top + rect.height / 2);
  }
  return result;
}

function makeCells(image, width, height) {
  const columns = Math.ceil(width / cellWidth);
  const rows = Math.ceil(height / cellHeight);
  const sample = document.createElement('canvas');
  sample.width = columns;
  sample.height = rows;
  const sampleContext = sample.getContext('2d', { willReadFrequently: true });
  sampleContext.drawImage(image, 0, 0, columns, rows);
  const pixels = sampleContext.getImageData(0, 0, columns, rows).data;
  const background = [pixels[0], pixels[1], pixels[2]];
  const lightBackground = luminance(background) > .5;
  const accent = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim().split(/\s+/).map(Number);
  const maxDistance = Math.max(...[
    Math.hypot(props.x, props.y), Math.hypot(width - props.x, props.y),
    Math.hypot(props.x, height - props.y), Math.hypot(width - props.x, height - props.y)
  ]);
  const remaining = document.createElement('canvas');
  remaining.width = width;
  remaining.height = height;
  const staticContext = remaining.getContext('2d');
  staticContext.font = '12px monospace';
  staticContext.textBaseline = 'middle';
  const cells = [];
  for (let row = 0; row < rows; row++) {
    for (let column = 0; column < columns; column++) {
      const offset = (row * columns + column) * 4;
      const color = [pixels[offset], pixels[offset + 1], pixels[offset + 2]];
      const density = lightBackground ? 1 - luminance(color) : luminance(color);
      const character = glyphs[Math.min(glyphs.length - 1, Math.floor(density * glyphs.length))];
      const x = column * cellWidth;
      const y = row * cellHeight;
      const cx = x + cellWidth / 2;
      const cy = y + cellHeight / 2;
      const distance = Math.hypot(cx - props.x, cy - props.y);
      const angle = Math.atan2(cy - props.y, cx - props.x);
      const jitter = ((column * 73 + row * 151 + column * row * 19) % 51) - 25;
      const cell = {
        x, y, color: rgb(color), ink: character === ' ' ? '' : themeInk(color, accent), character, angle,
        speed: 90 + 650 / (1 + distance / 170),
        launch: hold + 420 * distance / maxDistance + jitter,
        seed: column * 17 + row * 31
      };
      staticContext.fillStyle = cell.color;
      staticContext.fillRect(x, y, cellWidth, cellHeight);
      if (character !== ' ') {
        staticContext.fillStyle = cell.ink;
        staticContext.fillText(character, x + 1, y + cellHeight / 2);
      }
      cells.push(cell);
    }
  }
  cells.sort((a, b) => a.launch - b.launch);
  return { cells, remaining, staticContext };
}

function animate(state, startedAt) {
  const context = canvas.value?.getContext('2d');
  if (!context) return;
  const active = [];
  let nextCell = 0;
  const render = now => {
    const elapsed = Math.min(now - startedAt, duration);
    while (nextCell < state.cells.length && state.cells[nextCell].launch <= elapsed) {
      const cell = state.cells[nextCell++];
      state.staticContext.clearRect(cell.x, cell.y, cellWidth, cellHeight);
      active.push(cell);
    }
    context.clearRect(0, 0, canvas.value.width, canvas.value.height);
    context.drawImage(state.remaining, 0, 0);
    context.font = '12px monospace';
    context.textBaseline = 'middle';
    let retained = 0;
    for (const cell of active) {
      const age = (elapsed - cell.launch) / 1000;
      if (age > (cell.character === ' ' ? .16 : .44)) continue;
      active[retained++] = cell;
      const progress = age / .44;
      const dx = Math.cos(cell.angle) * cell.speed * age;
      const dy = Math.sin(cell.angle) * cell.speed * age + 480 * age * age;
      const blockAlpha = Math.max(0, 1 - age / .16);
      if (blockAlpha) {
        context.globalAlpha = blockAlpha;
        const scale = 1 - Math.min(age / .16, 1) * .22;
        context.fillStyle = cell.color;
        context.fillRect(cell.x + dx + cellWidth * (1 - scale) / 2, cell.y + dy + cellHeight * (1 - scale) / 2, cellWidth * scale, cellHeight * scale);
      }
      if (cell.character !== ' ') {
        context.globalAlpha = Math.max(0, 1 - progress);
        context.fillStyle = cell.ink;
        const character = age < .05 ? cell.character : glitch[(cell.seed + Math.floor(age * 35)) % glitch.length];
        context.fillText(character, cell.x + dx + 1, cell.y + dy + cellHeight / 2);
      }
    }
    active.length = retained;
    context.globalAlpha = 1;
    if (elapsed < duration) frame = requestAnimationFrame(render);
    else finish();
  };
  frame = requestAnimationFrame(render);
}

function finish() {
  if (finished) return;
  finished = true;
  emit('finished');
}

onMounted(async () => {
  const screen = document.querySelector('[data-connect-screen]');
  if (!screen) { finish(); return; }
  const width = window.innerWidth;
  const height = window.innerHeight;
  canvas.value.width = width;
  canvas.value.height = height;
  const startedAt = performance.now();
  let snapshot;
  try {
    snapshot = await captureScreen(screen, width, height);
    // 某些浏览器允许绘制 SVG，却拒绝读取像素。
    snapshot.getContext('2d').getImageData(0, 0, 1, 1);
  } catch {
    snapshot = captureFallback(screen, width, height);
  }
  if (finished) return;
  let state;
  try { state = makeCells(snapshot, width, height); }
  catch { state = makeCells(captureFallback(screen, width, height), width, height); }
  if (finished) return;
  timer = window.setTimeout(() => {
    canvas.value.getContext('2d').drawImage(state.remaining, 0, 0);
    canvas.value.style.visibility = 'visible';
    emit('dissolve');
    animate(state, performance.now() - hold);
  }, Math.max(0, hold - (performance.now() - startedAt)));
});

onBeforeUnmount(() => {
  finished = true;
  clearTimeout(timer);
  cancelAnimationFrame(frame);
});
</script>

<style scoped>
canvas { visibility: hidden; }
</style>
