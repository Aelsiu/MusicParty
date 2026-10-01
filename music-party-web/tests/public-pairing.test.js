import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createPublicPairingSession } from '../src/services/publicPairing.js';
import { pairingRemaining, pairingProgress, pairingCountdown } from '../src/utils/pairingClock.js';

const opened = (code = '0012', extra = {}) => ({ open: true, pairingCode: code,
    nextUpdateAt: 61000, serverTime: 1000, pairingIntervalMinutes: 1, ...extra });
function harness(fetchPairing = async () => opened()) {
    const history = [], requests = [];
    let time = 1000;
    const session = createPublicPairingSession({
        fetchPairing: id => { requests.push(id); return fetchPairing(id); },
        publish: value => history.push(value), now: () => time
    });
    return { session, history, requests, state: () => history.at(-1), setTime: value => { time = value; } };
}
function deferred() { let resolve; return { promise: new Promise(done => { resolve = done; }), resolve }; }

test('closed responses discard all code fields, including unexpected sensitive fields', async () => {
    const h = harness(async () => ({ open: false, pairingCode: '1234', nextUpdateAt: 61000 }));
    await h.session.start('one');
    assert.deepEqual(h.state(), { open: false, code: '', nextUpdateAt: 0, intervalMinutes: 10, offset: 0 });
    h.session.changed({ nextUpdateAt: 61000 });
    h.session.tick();
    assert.equal(h.requests.length, 1);
});

test('OPEN exposes only validated display fields and preserves leading zeroes', async () => {
    const h = harness(async () => opened('0012', { ownerId: 'never-retained' }));
    await h.session.start('one');
    assert.deepEqual(h.state(), { open: true, code: '0012', nextUpdateAt: 61000, intervalMinutes: 1, offset: 0 });
});

test('closing clears immediately and invalidates earlier successful requests', async () => {
    const pending = deferred(), h = harness(() => pending.promise);
    const refresh = h.session.start('one');
    h.session.changed({ open: false });
    assert.equal(h.state().code, '');
    pending.resolve(opened());
    await refresh;
    assert.equal(h.state().open, false);
    assert.equal(h.state().code, '');
});

test('closing an already visible code clears it before any further network response', async () => {
    const h = harness();
    await h.session.start('one');
    h.session.changed({ open: false });
    assert.equal(h.state().open, false);
    assert.equal(h.state().code, '');
    assert.equal(h.state().nextUpdateAt, 0);
});

test('reopening cannot be overwritten by a stale OPEN or closed response', async () => {
    const old = deferred(); let count = 0;
    const h = harness(() => ++count === 1 ? old.promise : Promise.resolve(opened('0987')));
    const initial = h.session.start('one');
    h.session.changed({ open: false });
    await h.session.changed({ open: true });
    old.resolve({ open: false });
    await initial;
    assert.equal(h.state().code, '0987');
});

test('disconnecting or leaving clears and rejects pending data', async () => {
    const pending = deferred(), h = harness(() => pending.promise);
    const initial = h.session.start('one');
    h.session.stop();
    pending.resolve(opened());
    await initial;
    assert.equal(h.state().code, '');
    await h.session.changed({ open: true });
    assert.equal(h.requests.length, 1);
});

test('switching rooms never displays a previous room code', async () => {
    const pending = deferred(), h = harness(id => id === 'one' ? pending.promise : Promise.resolve({ open: false }));
    const initial = h.session.start('one');
    await h.session.start('two');
    pending.resolve(opened());
    await initial;
    assert.equal(h.state().open, false);
    assert.deepEqual(h.requests, ['one', 'two']);
});

test('failed fetches fail closed and heartbeat can safely retry', async () => {
    let fail = false;
    const h = harness(async () => { if (fail) throw new Error('Forbidden'); return opened(); });
    await h.session.start('one');
    fail = true;
    await h.session.refresh();
    assert.equal(h.state().code, '');
    fail = false;
    await h.session.changed({ nextUpdateAt: 61000 });
    assert.equal(h.state().code, '0012');
});

test('invalid or expired OPEN responses never expose a code', async () => {
    for (const extra of [{ pairingCode: '12' }, { pairingCode: '12345' }, { pairingCode: '<svg>' }, { pairingCode: 1234 },
        { open: 'true' }, { nextUpdateAt: 1000 }, { serverTime: undefined },
        { pairingIntervalMinutes: 0 }, { pairingIntervalMinutes: 61 }, { pairingIntervalMinutes: 1.5 }]) {
        const h = harness(async () => opened('0012', extra));
        await h.session.start('one');
        assert.equal(h.state().code, '');
        assert.equal(h.state().open, false);
    }
});

test('expiry clears old code synchronously before requesting the next code', async () => {
    const pending = deferred(); let count = 0;
    const h = harness(() => ++count === 1 ? Promise.resolve(opened()) : pending.promise);
    await h.session.start('one');
    h.setTime(61000);
    const refresh = h.session.tick();
    assert.equal(h.state().code, '');
    pending.resolve(opened('0987', { serverTime: 61000, nextUpdateAt: 121000 }));
    await refresh;
    assert.equal(h.state().code, '0987');
});

test('server clock offset determines expiry, not the client wall clock', async () => {
    const h = harness();
    h.setTime(900000);
    await h.session.start('one');
    assert.equal(h.state().offset, -899000);
    h.setTime(959999);
    h.session.tick();
    assert.equal(h.state().code, '0012');
    assert.equal(h.requests.length, 1);
});

test('countdown handles 1, 10 and 60 minute intervals and offset', () => {
    for (const minutes of [1, 10, 60]) {
        const next = minutes * 60000 + 1000;
        assert.equal(pairingRemaining(next, 1000), minutes * 60);
        assert.equal(pairingProgress(next, minutes, 1000), 100);
        assert.equal(pairingProgress(next, minutes, 1000 + minutes * 30000), 50);
    }
    assert.equal(pairingRemaining(61000, 900000, -899000), 60);
    assert.equal(pairingCountdown(3600), '60:00');
    assert.equal(pairingCountdown(9), '00:09');
});

test('missing or expired deadlines and invalid intervals have an empty ring', () => {
    assert.equal(pairingRemaining(undefined), 0);
    assert.equal(pairingRemaining(1, 2), 0);
    for (const value of [undefined, 0, 61, 1.5]) assert.equal(pairingProgress(61000, value, 1000), 0);
    assert.equal(pairingProgress(61000, 1, -999999), 100);
});
