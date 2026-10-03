import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createReturnConfirmation } from '../src/services/returnConfirmation.js';

function confirmation({ immediate = false } = {}) {
    let time = 0, exits = 0, complete, timer, phase = 'idle';
    const transitions = [];
    const control = createReturnConfirmation({
        animation: {
            animate(from, to, duration, callback) { transitions.push({ from, to, duration }); complete = callback; if (immediate) callback(); },
            reset() { complete = null; }
        },
        confirm: () => exits++, publish: value => { phase = value; }, now: () => time,
        setTimer: (callback, delay) => { timer = { callback, at: time + delay }; return timer; },
        clearTimer: value => { if (timer === value) timer = null; }
    });
    return {
        control, transitions, finish: () => complete?.(), setTime: value => { time = value; },
        fireTimer: () => { const pending = timer; timer = null; pending?.callback(); },
        timer: () => timer, phase: () => phase, exits: () => exits
    };
}

test('RETURN ignores repeat clicks during scramble and starts a full three-second settled window', () => {
    const c = confirmation();
    assert.equal(c.control.click(), 'arming');
    assert.equal(c.control.click(), 'ignored');
    assert.equal(c.exits(), 0);
    assert.equal(c.timer(), undefined);
    c.setTime(280); c.finish();
    assert.equal(c.phase(), 'armed');
    assert.equal(c.timer().at, 3280);
    c.setTime(3279);
    assert.equal(c.control.click(), 'confirmed');
    assert.equal(c.exits(), 1);
    assert.equal(c.timer(), null);
});

test('a second click at the deadline cannot exit even when the timer is delayed', () => {
    const c = confirmation();
    c.control.click(); c.setTime(280); c.finish(); c.setTime(3280);
    assert.equal(c.control.click(), 'expired');
    assert.equal(c.exits(), 0);
    assert.equal(c.phase(), 'restoring');
    assert.equal(c.control.click(), 'ignored');
    assert.deepEqual(c.transitions, [
        { from: 'RETURN', to: 'SURE??', duration: 280 },
        { from: 'SURE??', to: 'RETURN', duration: 220 }
    ]);
    c.finish(); assert.equal(c.phase(), 'idle');
});

test('timeout restores RETURN without exiting and allows a new confirmation', () => {
    const c = confirmation();
    c.control.click(); c.setTime(280); c.finish(); c.setTime(3280); c.fireTimer();
    assert.equal(c.phase(), 'restoring'); assert.equal(c.exits(), 0);
    c.finish(); assert.equal(c.phase(), 'idle');
    assert.equal(c.control.click(), 'arming');
});

test('room changes cancel both an armed timer and a pending animation callback', () => {
    const c = confirmation();
    c.control.click(); c.finish(); const stale = c.timer(); c.control.reset(); stale.callback();
    assert.equal(c.phase(), 'idle'); assert.equal(c.exits(), 0);
    c.control.click(); c.control.reset(); c.finish();
    assert.equal(c.phase(), 'idle'); assert.equal(c.exits(), 0);
});

test('reduced motion still requires a second click within three seconds', () => {
    const c = confirmation({ immediate: true });
    c.control.click(); assert.equal(c.phase(), 'armed'); assert.equal(c.exits(), 0);
    assert.equal(c.timer().at, 3000);
    c.setTime(1500); c.control.click(); assert.equal(c.exits(), 1);
});
