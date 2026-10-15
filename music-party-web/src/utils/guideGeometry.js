export function intersectRect(a, b) {
    const left = Math.max(a.left, b.left), top = Math.max(a.top, b.top);
    const right = Math.min(a.right, b.right), bottom = Math.min(a.bottom, b.bottom);
    return right > left && bottom > top ? { left, top, right, bottom, width: right - left, height: bottom - top } : null;
}

export function viewportRect() {
    const width = window.innerWidth, height = window.innerHeight;
    return { left: 0, top: 0, right: width, bottom: height, width, height };
}

export function visibleGuideRect(element, bounds = viewportRect()) {
    if (!element?.getBoundingClientRect || element.closest?.('[data-guide-obscured="true"]')) return null;
    const rect = element.getBoundingClientRect();
    if (!(rect.width > 0 && rect.height > 0)) return null;
    let clipped = intersectRect(rect, bounds);
    for (let node = element; clipped && node; node = node.parentElement) {
        const style = window.getComputedStyle?.(node);
        if (node.hidden || style?.display === 'none' || style?.visibility === 'hidden' || style?.opacity === '0') return null;
        if (node !== element && style && /hidden|clip|auto|scroll/.test(`${style.overflow} ${style.overflowX} ${style.overflowY}`)) {
            clipped = intersectRect(clipped, node.getBoundingClientRect());
        }
    }
    if (!clipped || clipped.width < 8 || clipped.height < 8) return null;
    const x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;
    if (x < clipped.left || x > clipped.right || y < clipped.top || y > clipped.bottom) return null;
    return { ...clipped, x, y };
}

// Positions are viewport coordinates; the renderer converts them to its scope.
export function placeGuideCard(point, size, bounds, inset = 12, gap = 26) {
    inset = Math.max(0, Math.min(inset, (bounds.width - 1) / 2, (bounds.height - 1) / 2));
    const width = Math.min(size.width, Math.max(1, bounds.width - inset * 2));
    const height = Math.min(size.height, Math.max(1, bounds.height - inset * 2));
    const clamp = (value, min, max) => Math.min(Math.max(value, min), Math.max(min, max));
    const centeredX = clamp(point.x - width / 2, bounds.left + inset, bounds.right - inset - width);
    const centeredY = clamp(point.y - height / 2, bounds.top + inset, bounds.bottom - inset - height);
    const candidates = [
        ['up', centeredX, point.y - gap - height],
        ['down', centeredX, point.y + gap],
        ['left', point.x - gap - width, centeredY],
        ['right', point.x + gap, centeredY]
    ];
    const fits = ([, left, top]) => left >= bounds.left + inset && top >= bounds.top + inset
        && left + width <= bounds.right - inset && top + height <= bounds.bottom - inset;
    const fitting = candidates.find(fits);
    if (!fitting) {
        // Scroll a tall card instead of covering the dot that closes it on touch.
        const above = point.y - gap - bounds.top - inset;
        const below = bounds.bottom - inset - point.y - gap;
        const up = above >= 80 || below < 80 && above >= below;
        const room = up ? above : below;
        if (room > 0) {
            const available = Math.min(height, room);
            return { direction: up ? 'up' : 'down', left: centeredX,
                top: up ? point.y - gap - available : point.y + gap, width, maxHeight: available };
        }
    }
    const [direction, x, y] = fitting || candidates[0];
    return { direction, left: clamp(x, bounds.left + inset, bounds.right - inset - width),
        top: clamp(y, bounds.top + inset, bounds.bottom - inset - height), width, maxHeight: Math.max(1, bounds.height - inset * 2) };
}

export function placeGuideMarkers(points, bounds, reserved = []) {
    const occupied = [...reserved], half = 22;
    const offsets = [[0, 0]];
    for (const step of [52, 104, 156]) offsets.push([0, step], [0, -step], [-step, 0], [step, 0], [-step, step], [step, step], [-step, -step], [step, -step]);
    const overlaps = (a, b) => a.left < b.right + 2 && a.right > b.left - 2 && a.top < b.bottom + 2 && a.bottom > b.top - 2;
    const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
    return points.flatMap(point => {
        for (const [dx, dy] of offsets) {
            const x = clamp(point.x + dx, bounds.left + half, bounds.right - half);
            const y = clamp(point.y + dy, bounds.top + half, bounds.bottom - half);
            const rect = { left: x - half, right: x + half, top: y - half, bottom: y + half };
            if (occupied.some(other => overlaps(rect, other))) continue;
            occupied.push(rect); return [{ ...point, x, y }];
        }
        return [];
    });
}
