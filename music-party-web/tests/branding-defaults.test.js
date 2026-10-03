import assert from 'node:assert/strict';
import { afterEach, beforeEach, test } from 'node:test';
import { register } from 'node:module';
import { createPinia, setActivePinia } from 'pinia';

register(new URL('./helpers/source-resolution.js', import.meta.url));
globalThis.localStorage = {
    getItem: () => null,
    setItem() {},
    removeItem() {}
};
globalThis.sessionStorage = globalThis.localStorage;
globalThis.document = { documentElement: { dataset: {} } };
const { useUiStore } = await import('../src/stores/ui.js');
const { default: client } = await import('../src/api/client.js');
const originalGet = client.get;
let ui;

beforeEach(() => {
    setActivePinia(createPinia());
    ui = useUiStore();
});
afterEach(() => { client.get = originalGet; });

test('webpage displays the project attribution before server configuration arrives', () => {
    assert.equal(ui.authorName, 'ThorNex X Aelsiu');
    assert.equal(ui.backWords, 'MUSIC PARTY');
});

test('a failed configuration request retains the project attribution', async context => {
    context.mock.method(console, 'error', () => {});
    client.get = async path => {
        assert.equal(path, '/api/config');
        throw new Error('Server unavailable');
    };
    await ui.fetchConfig();
    assert.equal(ui.authorName, 'ThorNex X Aelsiu');
    assert.equal(ui.backWords, 'MUSIC PARTY');
});

test('server configuration continues to support explicitly customized attribution', async () => {
    client.get = async path => {
        assert.equal(path, '/api/config');
        return { authorName: 'Configured Author', backWords: 'Configured Background' };
    };
    await ui.fetchConfig();
    assert.equal(ui.authorName, 'Configured Author');
    assert.equal(ui.backWords, 'Configured Background');
});
