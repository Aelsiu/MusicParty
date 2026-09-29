import assert from 'node:assert/strict';
import { test } from 'node:test';
import { matchesPlaylistSong } from '../src/utils/playlistFilter.js';

const song = { name: '告白气球', artists: ['周杰伦'] };

test('playlist filter matches Chinese text, full pinyin, initials and artist', () => {
    for (const query of ['气球', 'gaobaiqiqiu', 'gbqq', '周杰', 'zhoujielun', 'zjl']) {
        assert.equal(matchesPlaylistSong(song, query), true, query);
    }
    assert.equal(matchesPlaylistSong(song, '晴天'), false);
});

test('playlist filter ignores case for latin titles', () => {
    assert.equal(matchesPlaylistSong({ name: 'Fade', artists: ['Alan Walker'] }, 'fad'), true);
    assert.equal(matchesPlaylistSong({ name: 'Fade', artists: ['Alan Walker'] }, 'WALKER'), true);
});
