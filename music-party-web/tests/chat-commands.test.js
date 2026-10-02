import test from 'node:test';
import assert from 'node:assert/strict';
import { commandChoices, completeCommand, parseChatCommand } from '../src/utils/chatCommands.js';

test('unique command prefixes complete with or without slashes, ignoring case', () => {
    for (const text of ['co', '//co', 'CO', '  //Co']) assert.equal(completeCommand(text, true), '//code');
    assert.equal(completeCommand('cl', true), '//clear');
    assert.equal(completeCommand('//ad', false), '//admin');
    assert.equal(completeCommand('st', false), '//stream');
});

test('ambiguous prefixes, non-commands, extra arguments and unavailable commands do not complete', () => {
    for (const manager of [false, true]) {
        for (const text of ['c', '//c', '', '//', 'hello', '//stream o', '//clear all extra', '//code a', '//code o', '//code open']) {
            assert.equal(completeCommand(text, manager), null, text);
        }
    }
    assert.equal(completeCommand('co', false), null);
    assert.equal(completeCommand('ju', false), null);
});

test('parameter completion follows the clear all clarification and parameter permissions', () => {
    assert.equal(completeCommand('//clear a', true), '//clear all');
    assert.equal(completeCommand('clear d', true), '//clear dead');
    assert.equal(completeCommand('//code c', true), '//code copy');
    assert.equal(completeCommand('//code on', true), '//code on');
    assert.equal(completeCommand('//code of', true), '//code off');
    assert.equal(completeCommand('//code on', false), null);
    assert.equal(completeCommand('//code of', false), null);
    assert.equal(completeCommand('//jump d', true), '//jump dog');
    assert.equal(completeCommand('//stream n', false), '//stream now');
    assert.equal(completeCommand('//clear a', false), null);
    assert.equal(completeCommand('//clear s', false), '//clear self');
});

test('shortcut menu only offers commands and parameters allowed to this room identity', () => {
    assert.deepEqual(commandChoices(false).map(item => item.text), ['//clear self', '//stream now', '//admin']);
    const manager = commandChoices(true).map(item => item.text);
    assert.equal(manager.length, 15);
    assert.equal(manager.includes('//code open'), false);
    for (const command of ['//clear all', '//clear dead', '//clear chat', '//stream on', '//stream off', '//jump dog', '//code copy', '//code on', '//code off', '//rooms']) {
        assert.ok(manager.includes(command));
    }
});

test('command parsing applies explicit defaults but does not accept invalid or extra parameters', () => {
    for (const [name, parameter] of [['clear', 'self'], ['stream', 'now'], ['jump', 'off'], ['code', 'copy'], ['admin', ''], ['rooms', '']]) {
        assert.deepEqual(parseChatCommand(`//${name}`), { name, parameter, valid: true });
    }
    assert.deepEqual(parseChatCommand('//CODE COPY'), { name: 'code', parameter: 'copy', valid: true });
    assert.deepEqual(parseChatCommand('//CODE ON'), { name: 'code', parameter: 'on', valid: true });
    assert.deepEqual(parseChatCommand('//code off'), { name: 'code', parameter: 'off', valid: true });
    assert.equal(parseChatCommand('//code open').valid, false);
    assert.equal(parseChatCommand('//code off extra').valid, false);
    assert.equal(parseChatCommand('//code all').valid, false);
    assert.equal(parseChatCommand('//code copy extra').valid, false);
    assert.equal(parseChatCommand('//rooms anything').valid, false);
    assert.equal(parseChatCommand('code'), null);
});
