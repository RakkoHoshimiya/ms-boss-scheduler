export type NoteBlock = { key: string; kind: 'text'; text: string } | { key: string; kind: 'image'; attachmentId: string };
let sequence = 0;
const key = () => `note-block-${++sequence}`;

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
export function noteSummary(content: string): string {
  return content.replace(/\[\[image:[a-zA-Z0-9-]+\]\]/g, ' [图片] ');
}


/** Read only text, line breaks and our own images; never persist pasted HTML. */
export function readNoteDocument(root: HTMLElement, imageIds: ReadonlySet<string>): string {
  function read(parent: Node): string {
    let text = '';
    const children = Array.from(parent.childNodes);
    children.forEach((node, index) => {
      if (node.nodeType === 3) { text += node.textContent ?? ''; return; }
      if (node.nodeType !== 1) return;
      const element = node as HTMLElement;
      if (['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT'].includes(element.tagName)) return;
      if (element.tagName === 'IMG') {
        const id = element.dataset.noteImage;
        if (id && imageIds.has(id)) text += `[[image:${id}]]`;
        return;
      }
      if (element.tagName === 'BR') { text += '\n'; return; }
      const block = ['DIV', 'P', 'LI', 'H1', 'H2', 'H3', 'BLOCKQUOTE'].includes(element.tagName);
      if (block && index > 0 && !text.endsWith('\n')) text += '\n';
      text += read(element);
      if (block && index < children.length - 1 && !text.endsWith('\n')) text += '\n';
    });
    return text;
  }
  return read(root);
}
