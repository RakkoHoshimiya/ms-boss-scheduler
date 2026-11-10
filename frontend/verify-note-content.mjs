import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { parseNoteContent, readNoteDocument, noteSummary } from './src/features/note-content.ts';
const dom = new JSDOM('<div></div>');
const root = dom.window.document.querySelector('div');
for (const content of ['', 'line 1\nline 2', 'before[[image:a]]after', '[[image:a]][[image:b]]', '<script>alert(1)</script>']) {
  root.replaceChildren();
  for (const block of parseNoteContent(content)) {
    if (block.kind === 'text') root.append(dom.window.document.createTextNode(block.text));
    else { const image = dom.window.document.createElement('img'); image.dataset.noteImage = block.attachmentId; root.append(image); }
  }
  assert.equal(readNoteDocument(root, new Set(['a','b'])), content);
  assert.equal(root.querySelector('script'), null);
}
root.innerHTML = 'hello<div><br></div><div>world</div>';
assert.equal(readNoteDocument(root, new Set()), 'hello\n\nworld');
root.innerHTML = '<p>safe <b>text</b></p><script>bad()</script><img src="https://external.invalid/a.png"><img data-note-image="forged">';
assert.equal(readNoteDocument(root, new Set()).trim(), 'safe text');
assert.equal(noteSummary('before[[image:a]]after'), 'before [图片] after');
console.log('NOTE_CONTENT_PASS: continuous document round-trip, line breaks and safe serialization');
dom.window.close();
