import { useEffect, useMemo, useRef, useState } from 'react';
import { notesApi, type GuideNote, type NoteDetail, type NoteAttachment, type NoteDraft } from '../api/client';
import { parseNoteContent } from '../features/note-content';
import NoteRichText, { type NoteRichTextHandle } from './NoteRichText';
import NoteAttachmentPreview from './NoteAttachmentPreview';
import Modal from './Modal';
import { useAuth } from './AuthContext';
import { cacheDraft, forgetDraft } from '../features/note-drafts';

export default function NoteEditor({ initial, draft, onSaved, onClose }: { initial?: NoteDetail; draft?: NoteDraft; onSaved: (note: GuideNote) => void; onClose: () => void }) {
  const { user } = useAuth();
  const seed = draft ?? initial?.note;
  const seedFiles = draft?.attachments ?? initial?.attachments ?? [];
  const [title, setTitle] = useState(seed?.title ?? '');
  const [pinned, setPinned] = useState(seed?.pinned ?? false);
  const [content, setContent] = useState(seed?.content ?? '');
  const [files, setFiles] = useState<NoteAttachment[]>(seedFiles);
  const initialInline = new Set(parseNoteContent(seed?.content ?? '').flatMap(block => block.kind === 'image' ? [block.attachmentId] : []));
  const [footerIds, setFooterIds] = useState(() => new Set(seedFiles.filter(file => !initialInline.has(file.id)).map(file => file.id)));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [progress, setProgress] = useState('');
  const editor = useRef<NoteRichTextHandle>(null);
  const staged = useRef(new Set<string>());
  const inlineIds = new Set(parseNoteContent(content).flatMap(block => block.kind === 'image' ? [block.attachmentId] : []));
  const usedFiles = files.filter(file => inlineIds.has(file.id) || footerIds.has(file.id));
  const footer = files.filter(file => footerIds.has(file.id));

  const draftId = useRef(draft?.id ?? crypto.randomUUID());
  const cloudVersion = useRef(draft?.version);
  const [draftStatus, setDraftStatus] = useState(draft ? '已恢复草稿' : '输入后自动保存为仅自己可见的草稿');
  const inFlight = useRef<Promise<NoteDraft | null> | null>(null);
  const stopped = useRef(false);
  const mounted = useRef(true);
  const conflict = useRef(false);
  const lastSaved = useRef('');
  const lastCloud = useRef<NoteDraft | null>(null);
  const signature = JSON.stringify({ title, content, pinned, attachmentIds: usedFiles.map(f => f.id) });
  const snapshot = useMemo<NoteDraft>(() => ({ id: draftId.current, noteId: draft?.noteId ?? initial?.note.id ?? null,
    sourceVersion: draft?.sourceVersion ?? initial?.note.version ?? null, title, content, pinned,
    attachments: usedFiles, updatedAt: new Date().toISOString() }), [signature]);
  const latest = useRef(snapshot); latest.current = snapshot;
  const initialSignature = useRef(JSON.stringify({ title: initial?.note.title ?? '', content: initial?.note.content ?? '',
    pinned: initial?.note.pinned ?? false, attachmentIds: (initial?.attachments ?? []).map(f => f.id) }));
  const hasDraft = Boolean(draft) || signature !== initialSignature.current || cloudVersion.current !== undefined;
  const shouldSave = useRef(hasDraft); shouldSave.current = hasDraft;
  function backup() {
    if (!user || stopped.current || !shouldSave.current) return true;
    return cacheDraft(user.id, { ...latest.current, version: cloudVersion.current });
  }
  async function flushDraft(): Promise<NoteDraft | null> {
    while (inFlight.current) await inFlight.current;
    if (stopped.current || !shouldSave.current) return null;
    if (conflict.current) throw new Error('草稿已在其他窗口更新，当前内容仍保留在本机，请关闭后重新打开草稿核对');
    const current = latest.current;
    const stamp = JSON.stringify({ ...current, version: undefined });
    if (stamp === lastSaved.current) return lastCloud.current;
    const task = notesApi.saveDraft({ ...current, version: cloudVersion.current, attachmentIds: current.attachments.map(f => f.id) });
    inFlight.current = task;
    try {
      const saved = await task;
      cloudVersion.current = saved.version; lastSaved.current = stamp; lastCloud.current = saved;
      backup();
      if (mounted.current) setDraftStatus('草稿已保存 · 仅自己可见');
      return saved;
    } catch (e) {
      if ((e as { code?: string }).code === 'CONCURRENT_UPDATE') conflict.current = true;
      if (mounted.current) setDraftStatus(`云端草稿未保存：${(e as Error).message}；${backup() ? '本机副本已保留' : '本机存储不可用，请勿关闭页面'}`);
      throw e;
    } finally { inFlight.current = null; }
  }
  useEffect(() => {
    mounted.current = true;
    const unload = (event: BeforeUnloadEvent) => {
      if (!stopped.current && shouldSave.current) {
        backup();
        if (inFlight.current || JSON.stringify({ ...latest.current, version: undefined }) !== lastSaved.current) { event.preventDefault(); event.returnValue = ''; }
      }
    };
    window.addEventListener('beforeunload', unload);
    return () => { mounted.current = false; backup(); window.removeEventListener('beforeunload', unload); };
  }, []);
  useEffect(() => {
    if (!hasDraft || stopped.current) return;
    const local = backup();
    setDraftStatus(local ? '本机副本已保留，正在保存草稿…' : '本机存储不可用，正在保存云端草稿…');
    const timer = window.setTimeout(() => { void flushDraft().catch(() => undefined); }, 900);
    return () => window.clearTimeout(timer);
  }, [signature]);

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
    setBusy(true);
    try { await flushDraft(); onClose(); }
    catch (e) {
      if (backup() && confirm('云端草稿暂未保存，本机恢复副本已保留。仍要关闭编辑吗？')) onClose();
      else { setError((e as Error).message); setBusy(false); }
    }
  }
  async function removeDraft() {
    if (busy || !confirm('删除这份草稿？服务器保留 7 天，可从回收站恢复。')) return;
    setBusy(true);
    try {
      const saved = await flushDraft();
      if (saved) await notesApi.deleteDraft(saved.id);
      stopped.current = true;
      if (user) forgetDraft(user.id, draftId.current);
      onClose();
    } catch (e) { setError((e as Error).message); setBusy(false); }
  }
  async function save() {
    if (busy) return;
    const documentContent = editor.current?.read() ?? content;
    const inDocument = new Set(parseNoteContent(documentContent).flatMap(block => block.kind === 'image' ? [block.attachmentId] : []));
    const selected = files.filter(file => footerIds.has(file.id) || inDocument.has(file.id));
    setBusy(true); setError('');
    try {
      const savedDraft = await flushDraft();
      const note = await notesApi.save({ id: snapshot.noteId ?? undefined, version: snapshot.sourceVersion ?? undefined, title, content: documentContent, pinned, attachmentIds: selected.map(file => file.id), draftId: savedDraft?.id, draftVersion: savedDraft?.version });
      const attached = new Set(selected.map(file => file.id));
      await Promise.allSettled([...staged.current].filter(id => !attached.has(id)).map(id => notesApi.discard(id)));
      stopped.current = true;
      if (user) forgetDraft(user.id, draftId.current);
      staged.current.clear(); onSaved(note);
    } catch (e) { setError((e as Error).message); setBusy(false); }
  }
  return <Modal title={snapshot.noteId ? '编辑便签' : '发布便签'} onClose={() => { void cancel(); }} footer={<>
    <button type="button" className="btn danger" disabled={busy} onClick={removeDraft}>删除草稿</button>
    <button type="button" className="btn" disabled={busy} onClick={cancel}>保存草稿并关闭</button>
    <button type="button" className="btn primary" disabled={busy || !title.trim() || !content.trim() || content.length > 50000} onClick={save}>{busy ? '正在处理…' : snapshot.noteId ? '保存修改' : '发布便签'}</button>
  </>}>
    <fieldset className="note-editor-fields" disabled={busy}>
      <label className="field"><span>标题</span><input type="text" maxLength={120} value={title} onChange={e => setTitle(e.target.value)} /></label>
      <div className="note-editor-toolbar" onMouseDown={() => editor.current?.remember()}>
        <label className="note-upload-button">＋ 插入图片<input aria-label="插入正文图片" type="file" multiple accept=".png,.jpg,.jpeg,.gif" onChange={e => { void upload(Array.from(e.target.files ?? []), 'inline'); e.target.value = ''; }} /></label>
        <label className="note-upload-button secondary">＋ 添加附件<input aria-label="添加底部附件" type="file" multiple accept=".png,.jpg,.jpeg,.gif,.webp,.pdf,.txt,.zip" onChange={e => { void upload(Array.from(e.target.files ?? []), 'footer'); e.target.value = ''; }} /></label>
      </div>
      <label className="field note-rich-label"><span>正文</span></label>
      <NoteRichText ref={editor} initialContent={seed?.content ?? ''} initialFiles={seedFiles} disabled={busy} onChange={setContent} onError={setError} onUpload={incoming => { void upload(incoming, 'inline'); }} />
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
    <p className="muted" role="status">{draftStatus}</p>
    {progress && <p role="status">{progress}</p>}
    {content.length > 50000 && <p className="note-error">内容超出 50,000 字符，请缩短后保存。</p>}
    {error && <p className="note-error" role="alert">{error}</p>}
  </Modal>;
}
