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

test('playlist filter matches partial romaji in hiragana, katakana and artist names', () => {
    assert.equal(matchesPlaylistSong({ name: 'マイライフ', artists: ['CY8ER'] }, 'raifu'), true);
    assert.equal(matchesPlaylistSong({ name: 'アディオス', artists: ['DAZBEE'] }, 'ADIOSU'), true);
    assert.equal(matchesPlaylistSong({ name: '夜に駆ける', artists: ['ヨルシカ'] }, 'yorushi'), true);
    assert.equal(matchesPlaylistSong({ name: 'がっこう', artists: [] }, 'gakkou'), true);
    assert.equal(matchesPlaylistSong({ name: 'さくら', artists: [] }, 'kur'), true);
    assert.equal(matchesPlaylistSong({ name: 'きゃりー', artists: [] }, 'kyarii'), true);
    assert.equal(matchesPlaylistSong({ name: 'マイライフ', artists: ['CY8ER'] }, 'sakura'), false);
});
