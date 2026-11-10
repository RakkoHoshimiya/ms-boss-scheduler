import { forwardRef, useImperativeHandle, useLayoutEffect, useRef } from 'react';
import { notesApi, type NoteAttachment } from '../api/client';
import { parseNoteContent, readNoteDocument } from '../features/note-content';

export interface NoteRichTextHandle {
  remember: () => void;
  insertImage: (file: NoteAttachment, local?: Blob) => void;
  read: () => string;
}
interface Props {
  initialContent: string;
  initialFiles: NoteAttachment[];
  disabled: boolean;
  onChange: (content: string) => void;
  onError: (message: string) => void;
  onUpload: (files: File[]) => void;
}

const NoteRichText = forwardRef<NoteRichTextHandle, Props>(function NoteRichText(props, ref) {
  const element = useRef<HTMLDivElement>(null);
  const selection = useRef<Range | null>(null);
  const images = useRef(new Set(props.initialFiles.map(file => file.id)));
  const urls = useRef(new Map<string, string>());
  const active = useRef(false);
  const callbacks = useRef(props);
  callbacks.current = props;

  function remember() {
    const current = window.getSelection();
    if (current?.rangeCount && element.current?.contains(current.getRangeAt(0).commonAncestorContainer)) {
      selection.current = current.getRangeAt(0).cloneRange();
    }
  }
  function read() { return element.current ? readNoteDocument(element.current, images.current) : ''; }
  function changed() { remember(); callbacks.current.onChange(read()); }
  function image(file: NoteAttachment, local?: Blob) {
    images.current.add(file.id);
    const img = document.createElement('img');
    img.dataset.noteImage = file.id; img.alt = file.name;
    img.draggable = false;
    function display(blob: Blob) {
      if (!active.current) return;
      let url = urls.current.get(file.id);
      if (!url) { url = URL.createObjectURL(blob); urls.current.set(file.id, url); }
      img.src = url;
    }
    const cached = urls.current.get(file.id);
    if (cached) img.src = cached;
    else if (local) display(local);
    else notesApi.file(file.id).then(display).catch(e => {
      if (active.current) { img.alt = `${file.name}（图片加载失败）`; callbacks.current.onError((e as Error).message); }
    });
    return img;
  }
  function range() {
    const root = element.current!;
    if (selection.current && root.contains(selection.current.commonAncestorContainer)) return selection.current.cloneRange();
    const next = document.createRange(); next.selectNodeContents(root); next.collapse(false); return next;
  }
  function insert(node: Node, trailing?: Node) {
    const root = element.current;
    if (!root) return;
    const at = range(); at.deleteContents();
    const fragment = document.createDocumentFragment(); fragment.append(node);
    if (trailing) fragment.append(trailing);
    at.insertNode(fragment);
    const caret = document.createRange();
    caret.setStartAfter(trailing ?? node); caret.collapse(true);
    selection.current = caret.cloneRange();
    root.focus(); const current = window.getSelection(); current?.removeAllRanges(); current?.addRange(caret);
    callbacks.current.onChange(read());
  }
  useLayoutEffect(() => {
    if (!props.disabled && selection.current && element.current?.contains(selection.current.commonAncestorContainer)) {
      element.current.focus();
      const current = window.getSelection();
      current?.removeAllRanges(); current?.addRange(selection.current.cloneRange());
    }
  }, [props.disabled]);
  useImperativeHandle(ref, () => ({
    remember, read,
    insertImage(file, local) { insert(image(file, local), document.createTextNode('\n')); },
  }));
  useLayoutEffect(() => {
    active.current = true;
    const root = element.current!;
    root.replaceChildren();
    for (const block of parseNoteContent(props.initialContent)) {
      if (block.kind === 'text') root.append(document.createTextNode(block.text));
      else {
        const file = props.initialFiles.find(item => item.id === block.attachmentId);
        if (file) root.append(image(file));
      }
    }
    return () => { active.current = false; for (const url of urls.current.values()) URL.revokeObjectURL(url); urls.current.clear(); };
    // This is an uncontrolled document: React must not replace it while the user types.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return <div ref={element} className="note-rich-editor" role="textbox" aria-label="攻略与提示" aria-multiline="true" aria-disabled={props.disabled} contentEditable={!props.disabled} suppressContentEditableWarning data-placeholder="在这里写攻略，图片会直接显示在正文中…"
    onInput={changed} onMouseUp={remember} onKeyUp={remember} onBlur={remember}
    onPaste={event => {
      event.preventDefault(); if (props.disabled) return;
      remember();
      const files = Array.from(event.clipboardData.files).filter(file => file.type.startsWith('image/'));
      if (files.length) { callbacks.current.onUpload(files); return; }
      const text = event.clipboardData.getData('text/plain');
      if (text) insert(document.createTextNode(text));
    }}
    onDrop={event => {
      event.preventDefault(); if (props.disabled) return;
      const files = Array.from(event.dataTransfer.files).filter(file => file.type.startsWith('image/'));
      if (files.length) callbacks.current.onUpload(files);
    }} onDragOver={event => event.preventDefault()} />;
});
export default NoteRichText;
