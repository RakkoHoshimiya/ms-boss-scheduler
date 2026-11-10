export type NoteBlock = { key: string; kind: 'text'; text: string } | { key: string; kind: 'image'; attachmentId: string };
export interface NoteCursor { key: string; start: number; end: number }
let sequence = 0;
const key = () => `note-block-${++sequence}`;
export const imageMarker = (id: string) => `[[image:${id}]]`;

export function parseNoteContent(content: string): NoteBlock[] {
  const result: NoteBlock[] = [];
  const pattern = /\[\[image:([a-zA-Z0-9-]+)\]\]/g;
  let offset = 0;
  for (const match of content.matchAll(pattern)) {
    result.push({ key: key(), kind: 'text', text: content.slice(offset, match.index) });
    result.push({ key: key(), kind: 'image', attachmentId: match[1] });
    offset = match.index! + match[0].length;
  }
  result.push({ key: key(), kind: 'text', text: content.slice(offset) });
  return result;
}
export function serializeNoteContent(blocks: NoteBlock[]): string {
  return blocks.map(block => block.kind === 'text' ? block.text : imageMarker(block.attachmentId)).join('');
}
export function noteSummary(content: string): string {
  return content.replace(/\[\[image:[a-zA-Z0-9-]+\]\]/g, ' [图片] ');
}
export function insertNoteImage(blocks: NoteBlock[], cursor: NoteCursor | null, attachmentId: string): NoteBlock[] {
  const target = blocks.find(block => block.key === cursor?.key && block.kind === 'text') ?? [...blocks].reverse().find(block => block.kind === 'text');
  if (!target || target.kind !== 'text') return blocks;
  const start = Math.min(target.text.length, cursor?.key === target.key ? cursor.start : target.text.length);
  const end = Math.min(target.text.length, cursor?.key === target.key ? cursor.end : target.text.length);
  const result = blocks.flatMap<NoteBlock>(block => {
    if (block.key !== target.key) return block.kind === 'image' && block.attachmentId === attachmentId ? [] : [block];
    return [
      { key: target.key, kind: 'text', text: target.text.slice(0, start) },
      { key: key(), kind: 'image', attachmentId },
      { key: key(), kind: 'text', text: target.text.slice(end) },
    ];
  });
  return result;
}
export function detachNoteImage(blocks: NoteBlock[], attachmentId: string): NoteBlock[] {
  return parseNoteContent(serializeNoteContent(blocks.filter(block => block.kind !== 'image' || block.attachmentId !== attachmentId)));
}
