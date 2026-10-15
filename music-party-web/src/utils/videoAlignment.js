import { toRaw } from 'vue';

// A native seeking event has no indication whether currentTime was assigned by
// our room follower or by a browser control. Keep that distinction on the element.
const alignments = new WeakMap();
const applied = new WeakSet();

export function markVideoAlignment(video, from, target) {
    video = toRaw(video);
    // An extension's render clock may already be between authoritative samples.
    // Rebase from the clock used to draw text, keeping its age continuous.
    const alignment = { from: video.presentationTime ?? from, target };
    alignments.set(video, alignment);
    return () => { if (alignments.get(video) === alignment) alignments.delete(video); };
}

function matchingAlignment(video, consume) {
    video = toRaw(video);
    const alignment = alignments.get(video);
    if (consume) alignments.delete(video);
    // Ignore a marker superseded by a native seek or an ignored assignment.
    return alignment && Math.abs(video.currentTime - alignment.target) < .05 ? alignment : null;
}

export function consumeVideoAlignment(video) {
    return matchingAlignment(video, true);
}

export function applyVideoAlignment(video, apply, consume = false) {
    const alignment = matchingAlignment(video, consume);
    if (!alignment) return false;
    // Rendering may precede the queued native seeking event. Either path applies
    // the correction once; the event still consumes its marker afterwards.
    if (!applied.has(alignment)) { applied.add(alignment); apply(alignment); }
    return true;
}
