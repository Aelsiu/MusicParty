import assert from 'node:assert/strict';
import { test } from 'node:test';
import { copyAsyncText } from '../src/utils/clipboard.js';
import { copyRoomPairingCode, isPairingCodeCommand } from '../src/services/pairingCode.js';
import { isPairingCode, normalizePairingCode } from '../src/utils/pairingCode.js';

function request(overrides = {}) {
    const notices = [], copied = [], fetched = [];
    const dependencies = {
        roomId: 'current-room', managerToken: 'manager',
        manage: async id => { fetched.push(id); return { pairingCode: '0012' }; },
        copy: async text => copied.push(await text),
        notify: text => notices.push(text),
        ...overrides
    };
    return { dependencies, notices, copied, fetched };
}

test('code command matches server casing and whitespace without matching other commands', () => {
    for (const text of ['//code', ' //CODE ', '//Code ignored-args']) assert.equal(isPairingCodeCommand(text), true);
    for (const text of ['//codes', 'code', 'hello //code', '//rooms']) assert.equal(isPairingCodeCommand(text), false);
});

test('pairing codes accept four ASCII letters or digits with any case', () => {
    for (const code of ['1234', '0000', 'abcd', 'AZ09', 'a0Zb']) assert.equal(isPairingCode(code), true);
    for (const code of ['abc', '12345', '', ' abcd', 'abcd ', 'a-b1', 'ab汉1', 'ＡＢ１２', 1234, null]) {
        assert.equal(isPairingCode(code), false);
    }
    assert.equal(normalizePairingCode('A0ZB'), 'a0zb');
});

test('alphanumeric codes are copied from the latest manager response in canonical lowercase', async () => {
    const { dependencies, copied } = request({ manage: async () => ({ pairingCode: 'A0Zb' }) });
    assert.equal(await copyRoomPairingCode(dependencies), true);
    assert.deepEqual(copied, ['a0zb']);
});

test('each invocation gets the latest authorized code and preserves leading zeroes', async () => {
    const { dependencies, notices, copied, fetched } = request();
    assert.equal(await copyRoomPairingCode(dependencies), true);
    dependencies.manage = async id => { fetched.push(id); return { pairingCode: '0987' }; };
    assert.equal(await copyRoomPairingCode(dependencies), true);
    assert.deepEqual(copied, ['0012', '0987']);
    assert.deepEqual(fetched, ['current-room', 'current-room']);
    assert.deepEqual(notices, ['配对码已复制', '配对码已复制']);
});

test('a User without management access gets private guidance without requesting or copying codes', async () => {
    const { dependencies, notices, copied, fetched } = request({ managerToken: '' });
    assert.equal(await copyRoomPairingCode(dependencies), false);
    assert.deepEqual(copied, []);
    assert.deepEqual(fetched, []);
    assert.match(notices[0], /\/\/admin/);
});

test('expired or unrelated management access never copies or claims success', async () => {
    const { dependencies, notices, copied } = request({
        manage: async () => { throw { response: { data: { message: '无权管理该房间' } } }; }
    });
    assert.equal(await copyRoomPairingCode(dependencies), false);
    assert.deepEqual(copied, []);
    assert.deepEqual(notices, ['无权管理该房间']);
});

test('clipboard denial reports failure only after the code request is handled', async () => {
    const { dependencies, notices } = request({ copy: async () => { throw new Error('Denied'); } });
    assert.equal(await copyRoomPairingCode(dependencies), false);
    assert.deepEqual(notices, ['无法访问剪贴板，请在房间管理面板中复制配对码']);
});

test('a simultaneous API and clipboard rejection reports the API failure', async () => {
    const { dependencies, notices } = request({
        copy: async () => { throw new Error('Denied'); },
        manage: async () => { throw { response: { data: { message: '许可已失效' } } }; }
    });
    assert.equal(await copyRoomPairingCode(dependencies), false);
    assert.deepEqual(notices, ['许可已失效']);
});

test('leaving or switching the room while fetching cancels copying and feedback', async () => {
    let resolve, active = true;
    const { dependencies, notices, copied } = request({
        manage: () => new Promise(done => { resolve = done; }), isCurrent: () => active
    });
    const pending = copyRoomPairingCode(dependencies);
    await Promise.resolve();
    active = false;
    resolve({ pairingCode: '1234' });
    assert.equal(await pending, false);
    assert.deepEqual(copied, []);
    assert.deepEqual(notices, []);
});

test('success is reported only after the clipboard write completes', async () => {
    let finish;
    const { dependencies, notices } = request({
        copy: () => new Promise(done => { finish = done; })
    });
    const pending = copyRoomPairingCode(dependencies);
    assert.deepEqual(notices, []);
    finish();
    assert.equal(await pending, true);
    assert.deepEqual(notices, ['配对码已复制']);
});

test('ClipboardItem write starts synchronously before asynchronous code retrieval completes', async () => {
    let resolve, started = false, copied;
    const text = new Promise(done => { resolve = done; });
    class ClipboardItem {
        constructor(data) { this.data = data; }
    }
    const clipboard = {
        write: async ([item]) => {
            started = true;
            copied = await (await item.data['text/plain']).text();
        }
    };
    const pending = copyAsyncText(text, { clipboard, ClipboardItem });
    assert.equal(started, true);
    resolve('0012');
    await pending;
    assert.equal(copied, '0012');
});

test('browsers without promise ClipboardItems use writeText with the freshly resolved value', async () => {
    const copied = [];
    await copyAsyncText(Promise.resolve('0012'), {
        clipboard: { writeText: async text => copied.push(text) }, ClipboardItem: null
    });
    assert.deepEqual(copied, ['0012']);
});

test('HTTP fallback checks copy success and restores the focused input and selection', async () => {
    const calls = [];
    const input = { style: {}, select: () => calls.push(['select']), remove: () => calls.push(['remove']) };
    const document = {
        activeElement: {
            selectionStart: 2, selectionEnd: 4,
            focus: () => calls.push(['focus']), setSelectionRange: (...range) => calls.push(['selection', ...range])
        },
        createElement: () => input,
        body: { appendChild: () => calls.push(['append']) },
        execCommand: command => { calls.push([command, input.value]); return true; }
    };
    await copyAsyncText('0012', { clipboard: null, ClipboardItem: null, document });
    assert.deepEqual(calls, [['append'], ['select'], ['copy', '0012'], ['remove'], ['focus'], ['selection', 2, 4]]);
    document.execCommand = () => false;
    await assert.rejects(copyAsyncText('0012', { clipboard: null, ClipboardItem: null, document }), /Copy rejected/);
});

test('code retrieval errors never write placeholder text through compatibility paths', async () => {
    let writes = 0;
    await assert.rejects(copyAsyncText(Promise.reject(new Error('Forbidden')), {
        clipboard: { writeText: async () => { writes++; } }, ClipboardItem: null
    }), /Forbidden/);
    assert.equal(writes, 0);
});
