import { useRef, useState } from 'react';
import { notesApi, type GuideNote, type NoteDetail, type NoteAttachment } from '../api/client';
import { parseNoteContent } from '../features/note-content';
import NoteRichText, { type NoteRichTextHandle } from './NoteRichText';
import NoteAttachmentPreview from './NoteAttachmentPreview';
import Modal from './Modal';

export default function NoteEditor({ initial, onSaved, onClose }: { initial?: NoteDetail; onSaved: (note: GuideNote) => void; onClose: () => void }) {
  const [title, setTitle] = useState(initial?.note.title ?? '');
  const [pinned, setPinned] = useState(initial?.note.pinned ?? false);
  const [content, setContent] = useState(initial?.note.content ?? '');
  const [files, setFiles] = useState<NoteAttachment[]>(initial?.attachments ?? []);
  const initialInline = new Set(parseNoteContent(initial?.note.content ?? '').flatMap(block => block.kind === 'image' ? [block.attachmentId] : []));
  const [footerIds, setFooterIds] = useState(() => new Set((initial?.attachments ?? []).filter(file => !initialInline.has(file.id)).map(file => file.id)));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [progress, setProgress] = useState('');
  const editor = useRef<NoteRichTextHandle>(null);
  const staged = useRef(new Set<string>());
  const inlineIds = new Set(parseNoteContent(content).flatMap(block => block.kind === 'image' ? [block.attachmentId] : []));
  const usedFiles = files.filter(file => inlineIds.has(file.id) || footerIds.has(file.id));
  const footer = files.filter(file => footerIds.has(file.id));

  async function upload(incoming: File[], placement: 'inline' | 'footer') {
    if (!incoming.length || busy) return;
    if (usedFiles.length + incoming.length > 12) { setError('每张便签最多 12 个附件'); return; }
    if (incoming.some(file => file.size === 0 || file.size > 20 * 1024 * 1024)) { setError('单个附件须大于 0 且不超过 20 MB'); return; }
    setBusy(true); setError('');
    try {
      for (const [index, file] of incoming.entries()) {
        setProgress(`正在上传 ${index + 1}/${incoming.length}：${file.name}`);
        const attachment = await notesApi.stage(file);
        staged.current.add(attachment.id);
        setFiles(current => [...current, attachment]);
        if (placement === 'inline' && attachment.mediaType.startsWith('image/')) editor.current?.insertImage(attachment, file);
        else {
          setFooterIds(current => new Set([...current, attachment.id]));
          if (placement === 'inline') setError('这张图片暂不支持正文显示，已放入底部附件。请使用 PNG、JPG 或 GIF。');
        }
      }
    } catch (e) { setError(`上传未完成：${(e as Error).message}。已上传的内容会保留。`); }
    finally { setBusy(false); setProgress(''); }
  }
  function insertFooter(file: NoteAttachment) {
    editor.current?.insertImage(file);
    setFooterIds(current => { const next = new Set(current); next.delete(file.id); return next; });
  }
  async function removeFooter(file: NoteAttachment) {
    if (busy) return;
    setBusy(true); setError('');
    try {
      if (staged.current.has(file.id)) { await notesApi.discard(file.id); staged.current.delete(file.id); }
      setFooterIds(current => { const next = new Set(current); next.delete(file.id); return next; });
      setFiles(current => current.filter(item => item.id !== file.id));
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  async function cancel() {
    if (busy) return;
    if ((title.trim() || content.trim() || files.length) && !confirm('放弃本次编辑？尚未保存的内容和新上传的附件将被丢弃。')) return;
    setBusy(true);
    await Promise.allSettled([...staged.current].map(id => notesApi.discard(id)));
    onClose();
  }
  async function save() {
    if (busy) return;
    const documentContent = editor.current?.read() ?? content;
    const inDocument = new Set(parseNoteContent(documentContent).flatMap(block => block.kind === 'image' ? [block.attachmentId] : []));
    const selected = files.filter(file => footerIds.has(file.id) || inDocument.has(file.id));
    setBusy(true); setError('');
    try {
      const note = await notesApi.save({ id: initial?.note.id, version: initial?.note.version, title, content: documentContent, pinned, attachmentIds: selected.map(file => file.id) });
      const attached = new Set(selected.map(file => file.id));
      await Promise.allSettled([...staged.current].filter(id => !attached.has(id)).map(id => notesApi.discard(id)));
      staged.current.clear(); onSaved(note);
    } catch (e) { setError((e as Error).message); setBusy(false); }
  }
  return <Modal title={initial ? '编辑便签' : '发布便签'} onClose={() => { void cancel(); }} footer={<>
    <button type="button" className="btn" disabled={busy} onClick={cancel}>取消</button>
    <button type="button" className="btn primary" disabled={busy || !title.trim() || !content.trim() || content.length > 50000} onClick={save}>{busy ? '正在处理…' : initial ? '保存修改' : '发布便签'}</button>
  </>}>
    <fieldset className="note-editor-fields" disabled={busy}>
      <label className="field"><span>标题</span><input type="text" maxLength={120} value={title} onChange={e => setTitle(e.target.value)} /></label>
      <div className="note-editor-toolbar" onMouseDown={() => editor.current?.remember()}>
        <label className="note-upload-button">＋ 插入图片<input aria-label="插入正文图片" type="file" multiple accept=".png,.jpg,.jpeg,.gif" onChange={e => { void upload(Array.from(e.target.files ?? []), 'inline'); e.target.value = ''; }} /></label>
        <label className="note-upload-button secondary">＋ 添加附件<input aria-label="添加底部附件" type="file" multiple accept=".png,.jpg,.jpeg,.gif,.webp,.pdf,.txt,.zip" onChange={e => { void upload(Array.from(e.target.files ?? []), 'footer'); e.target.value = ''; }} /></label>
      </div>
      <label className="field note-rich-label"><span>正文</span></label>
      <NoteRichText ref={editor} initialContent={initial?.note.content ?? ''} initialFiles={initial?.attachments ?? []} disabled={busy} onChange={setContent} onError={setError} onUpload={incoming => { void upload(incoming, 'inline'); }} />
      {footer.length > 0 && <section className="note-footer-editor"><h3>底部附件</h3>{footer.map(file => <div className="note-asset-row" key={file.id}>
        <NoteAttachmentPreview file={file} compact />
        <div className="note-asset-actions" onMouseDown={() => editor.current?.remember()}>
          {file.mediaType.startsWith('image/') && <button type="button" className="btn small" onClick={() => insertFooter(file)}>插入正文</button>}
          <button type="button" className="btn small" onClick={() => removeFooter(file)}>移除</button>
        </div>
      </div>)}</section>}
      <label className="note-pin-field"><input type="checkbox" checked={pinned} onChange={e => setPinned(e.target.checked)} />置顶提醒</label>
    </fieldset>
    <p className="muted note-upload-limit">{usedFiles.length}/12 个文件 · 单个最大 20 MB</p>
    {progress && <p role="status">{progress}</p>}
    {content.length > 50000 && <p className="note-error">内容超出 50,000 字符，请缩短后保存。</p>}
    {error && <p className="note-error" role="alert">{error}</p>}
  </Modal>;
}
