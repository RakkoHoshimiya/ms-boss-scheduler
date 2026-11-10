import assert from 'node:assert/strict';
import { parseNoteContent, serializeNoteContent, insertNoteImage, detachNoteImage, noteSummary } from './src/features/note-content.ts';
for (const content of ['', 'line 1\nline 2', 'before[[image:a]]after', '[[image:a]][[image:b]]', '<script>alert(1)</script>']) {
  assert.equal(serializeNoteContent(parseNoteContent(content)), content);
}
const blocks = parseNoteContent('beforeafter');
const inserted = insertNoteImage(blocks, { key: blocks[0].key, start: 6, end: 6 }, 'a');
assert.equal(serializeNoteContent(inserted), 'before[[image:a]]after');
assert.equal(serializeNoteContent(detachNoteImage(inserted, 'a')), 'beforeafter');
const moved = insertNoteImage(inserted, { key: inserted[2].key, start: 5, end: 5 }, 'a');
assert.equal(serializeNoteContent(moved), 'beforeafter[[image:a]]');
assert.equal(noteSummary('before[[image:a]]after'), 'before [图片] after');
console.log('NOTE_CONTENT_PASS: round-trip, cursor insertion, move, detach and summaries');
