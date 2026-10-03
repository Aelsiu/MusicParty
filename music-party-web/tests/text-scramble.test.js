import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createTextScrambler } from '../src/utils/textScramble.js';

function animation(reduced = false) {
    let time = 0, nextId = 0, output, done = 0;
    const frames = new Map();
    const control = createTextScrambler({
        publish: value => { output = value; }, reducedMotion: () => reduced, now: () => time,
        requestFrame: callback => { frames.set(++nextId, callback); return nextId; }, cancelFrame: id => frames.delete(id), random: () => .5
    });
    return {
        control, finish: () => done++, done: () => done, output: () => output, frames: () => frames.size,
        tick: value => { time = value; const callbacks = [...frames.values()]; frames.clear(); callbacks.forEach(callback => callback(time)); }
    };
}

test('scramble shows substituted glyphs then settles exactly to the destination', () => {
    const a = animation();
    a.control.animate('SHARE', 'COPIED', 280, a.finish);
    a.tick(64);
    assert.equal(a.output().length, 6);
    assert.ok(a.output().some(part => part.dud));
    assert.equal(a.done(), 0);
    a.tick(160);
    assert.equal(a.output()[0].text, 'C'); assert.equal(a.output()[0].dud, false);
    a.tick(280);
    assert.deepEqual(a.output(), [{ text: 'COPIED', dud: false }]);
    assert.equal(a.done(), 1); assert.equal(a.frames(), 0);
});

test('cancelling a room animation prevents its completion and reset restores plain text', () => {
    const a = animation();
    a.control.animate('RETURN', 'SURE??', 280, a.finish);
    a.tick(64); a.control.reset('RETURN'); a.tick(280);
    assert.deepEqual(a.output(), [{ text: 'RETURN', dud: false }]);
    assert.equal(a.done(), 0); assert.equal(a.frames(), 0);
});

test('reduced motion immediately settles without scheduling animation frames', () => {
    const a = animation(true);
    a.control.animate('RETURN', 'SURE??', 280, a.finish);
    assert.deepEqual(a.output(), [{ text: 'SURE??', dud: false }]);
    assert.equal(a.done(), 1); assert.equal(a.frames(), 0);
});
