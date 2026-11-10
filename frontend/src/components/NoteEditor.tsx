import { useRef, useState } from 'react';
import { notesApi, type GuideNote, type NoteDetail, type NoteAttachment } from '../api/client';
import { parseNoteContent, serializeNoteContent, insertNoteImage, detachNoteImage, type NoteCursor } from '../features/note-content';
import NoteAttachmentPreview from './NoteAttachmentPreview';
import Modal from './Modal';

export default function NoteEditor({ initial, onSaved, onClose }: { initial?: NoteDetail; onSaved: (note: GuideNote) => void; onClose: () => void }) {
  const [title, setTitle] = useState(initial?.note.title ?? '');
  const [pinned, setPinned] = useState(initial?.note.pinned ?? false);
  const [blocks, setBlocks] = useState(() => parseNoteContent(initial?.note.content ?? ''));
  const [files, setFiles] = useState<NoteAttachment[]>(initial?.attachments ?? []);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [progress, setProgress] = useState('');
  const cursor = useRef<NoteCursor | null>(null);
  const staged = useRef(new Set<string>());
  const content = serializeNoteContent(blocks);
  const inline = new Set(blocks.flatMap(block => block.kind === 'image' ? [block.attachmentId] : []));

  function insert(file: NoteAttachment) {
    setBlocks(current => insertNoteImage(current, cursor.current, file.id));
    cursor.current = null;
  }
  async function upload(selected: FileList | null, placement: 'inline' | 'footer') {
    if (!selected?.length || busy) return;
    const incoming = Array.from(selected);
    if (files.length + incoming.length > 12) { setError('每张便签最多 12 个附件'); return; }
    if (incoming.some(file => file.size === 0 || file.size > 20 * 1024 * 1024)) { setError('单个附件须大于 0 且不超过 20 MB'); return; }
    setBusy(true); setError('');
    try {
      let currentBlocks = blocks;
      let at = cursor.current;
      for (const [index, file] of incoming.entries()) {
        setProgress(`正在上传 ${index + 1}/${incoming.length}：${file.name}`);
        const attachment = await notesApi.stage(file);
        staged.current.add(attachment.id);
        setFiles(current => [...current, attachment]);
        if (placement === 'inline') {
          if (!attachment.mediaType.startsWith('image/')) {
            setError('这类图片暂不支持正文预览，已放入底部附件。PNG、JPG、GIF 可插入正文。');
          } else {
            currentBlocks = insertNoteImage(currentBlocks, at, attachment.id);
            setBlocks(currentBlocks);
            const imageIndex = currentBlocks.findIndex(block => block.kind === 'image' && block.attachmentId === attachment.id);
            at = { key: currentBlocks[imageIndex + 1].key, start: 0, end: 0 };
            cursor.current = at;
          }
        }
      }
    } catch (e) { setError(`上传未完成：${(e as Error).message}。已上传的文件会保留，可重试剩余文件。`); }
    finally { setBusy(false); setProgress(''); }
  }
  async function removeFile(file: NoteAttachment) {
    if (busy) return;
    setBusy(true); setError('');
    try {
      if (staged.current.has(file.id)) { await notesApi.discard(file.id); staged.current.delete(file.id); }
      setFiles(current => current.filter(item => item.id !== file.id));
      setBlocks(current => detachNoteImage(current, file.id)); cursor.current = null;
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  async function cancel() {
    if (busy) return;
    if ((title.trim() || content.trim() || files.length) && !confirm('放弃本次编辑？尚未保存的内容和新上传的附件将被丢弃。')) return;
    setBusy(true);
    // Failed cleanup is retried by the server's temporary-file cleanup job.
    await Promise.allSettled([...staged.current].map(id => notesApi.discard(id)));
    onClose();
  }
  async function save() {
    if (busy) return;
    setBusy(true); setError('');
    try {
      const note = await notesApi.save({ id: initial?.note.id, title, content, pinned, attachmentIds: files.map(file => file.id) });
      staged.current.clear(); onSaved(note);
    } catch (e) { setError((e as Error).message); setBusy(false); }
  }
  return <Modal title={initial ? '编辑便签' : '发布便签'} onClose={() => { void cancel(); }} footer={<>
    <button type="button" className="btn" disabled={busy} onClick={cancel}>取消</button>
    <button type="button" className="btn primary" disabled={busy || !title.trim() || !content.trim() || content.length > 50000} onClick={save}>{busy ? '正在处理…' : initial ? '保存修改' : '发布便签'}</button>
  </>}>
    <fieldset className="note-editor-fields" disabled={busy}>
      <label className="field"><span>标题</span><input type="text" maxLength={120} value={title} onChange={e => setTitle(e.target.value)} /></label>
      <div className="note-editor-toolbar">
        <label className="note-upload-button">＋ 插入正文图片<input aria-label="插入正文图片" type="file" multiple accept=".png,.jpg,.jpeg,.gif" onChange={e => { void upload(e.target.files, 'inline'); e.target.value = ''; }} /></label>
        <label className="note-upload-button secondary">＋ 添加底部附件<input aria-label="添加底部附件" type="file" multiple accept=".png,.jpg,.jpeg,.gif,.webp,.pdf,.txt,.zip" onChange={e => { void upload(e.target.files, 'footer'); e.target.value = ''; }} /></label>
      </div>
      <p className="muted note-editor-help">先在文字中点选位置，再插入图片。图片前后都可以继续写攻略；底部附件会集中显示在详情末尾。</p>
      <div className="note-block-editor" aria-label="攻略与提示">
        {blocks.map((block, index) => block.kind === 'text' ? <label className="field note-text-block" key={block.key}>
          <span>{index === 0 ? '攻略与提示' : '继续写攻略'}</span>
          <textarea className="note-editor" rows={blocks.length === 1 ? 8 : 3} aria-label={index === 0 ? '攻略与提示' : `图片后的文字 ${index}`} placeholder="在这里写攻略内容…" value={block.text} maxLength={50000}
            onChange={e => setBlocks(current => current.map(item => item.key === block.key ? { ...block, text: e.target.value } : item))}
            onSelect={e => { cursor.current = { key: block.key, start: e.currentTarget.selectionStart, end: e.currentTarget.selectionEnd }; }}
            onFocus={e => { cursor.current = { key: block.key, start: e.currentTarget.selectionStart, end: e.currentTarget.selectionEnd }; }} />
        </label> : <figure className="note-inline-editor" key={block.key}>
          {files.find(file => file.id === block.attachmentId) && <NoteAttachmentPreview file={files.find(file => file.id === block.attachmentId)!} />}
          <figcaption><span>正文图片</span><button type="button" className="btn small" onClick={() => { setBlocks(current => detachNoteImage(current, block.attachmentId)); cursor.current = null; }}>改为底部附件</button></figcaption>
        </figure>)}
      </div>
      <h3 className="note-asset-heading">已上传文件 <small>{files.length}/12</small></h3>
      {files.length === 0 ? <p className="muted">还没有图片或附件，可以直接在上方添加。</p> : <div className="note-asset-list">{files.map(file => <div className="note-asset-row" key={file.id}>
        <NoteAttachmentPreview file={file} compact />
        <span className="note-placement">{inline.has(file.id) ? '正文图片' : '底部附件'}</span>
        <div className="note-asset-actions">
          {file.mediaType.startsWith('image/') && <button type="button" className="btn small" onClick={() => insert(file)}>{inline.has(file.id) ? '移至光标处' : '插入正文'}</button>}
          {inline.has(file.id) && <button type="button" className="btn small" onClick={() => { setBlocks(current => detachNoteImage(current, file.id)); cursor.current = null; }}>作为附件</button>}
          <button type="button" className="btn small" onClick={() => removeFile(file)}>移除</button>
        </div>
      </div>)}</div>}
      <label className="note-pin-field"><input type="checkbox" checked={pinned} onChange={e => setPinned(e.target.checked)} />置顶提醒</label>
    </fieldset>
    <p className="muted">单个附件最大 20 MB，每张便签最多 12 个。上传后点击发布或保存，内容才会对大家更新。</p>
    {progress && <p role="status">{progress}</p>}
    {content.length > 50000 && <p className="note-error">内容超出 50,000 字符，请缩短后保存。</p>}
    {error && <p className="note-error" role="alert">{error}</p>}
  </Modal>;
}
