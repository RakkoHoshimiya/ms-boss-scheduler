import { useEffect, useMemo, useState } from 'react';
import { notesApi, type GuideNote, type NoteDetail, type NoteDraft } from '../api/client';
import { parseNoteContent, noteSummary } from '../features/note-content';
import Modal from '../components/Modal';
import { useAuth } from '../components/AuthContext';
import { cachedDrafts, forgetDraft, mergeDrafts } from '../features/note-drafts';
import NoteEditor from '../components/NoteEditor';
import NoteAttachmentPreview from '../components/NoteAttachmentPreview';

function NoteBody({ detail }: { detail: NoteDetail }) {
  const blocks = useMemo(() => parseNoteContent(detail.note.content), [detail.note.content]);
  const inline = new Set(blocks.flatMap(block => block.kind === 'image' ? [block.attachmentId] : []));
  const footer = detail.attachments.filter(file => !inline.has(file.id));
  return <>
    <div className="note-content">{blocks.map(block => {
      if (block.kind === 'text') return <div className="note-body-text" key={block.key}>{block.text}</div>;
      const file = detail.attachments.find(item => item.id === block.attachmentId);
      return file ? <figure className="note-body-image" key={block.key}><NoteAttachmentPreview file={file} /></figure> : <p className="muted" key={block.key}>这张图片已被移除。</p>;
    })}</div>
    {footer.length > 0 && <section className="note-footer-attachments"><h3>附件 <small>{footer.length}</small></h3><div className="note-files">{footer.map(file => <NoteAttachmentPreview key={file.id} file={file} />)}</div></section>}
  </>;
}

export default function NotesPage() {
  const { user } = useAuth();
  const [drafts, setDrafts] = useState<NoteDraft[]>([]);
  const [draft, setDraft] = useState<NoteDraft>();
  const [trash, setTrash] = useState<GuideNote[]>([]);
  const [draftTrash, setDraftTrash] = useState<NoteDraft[]>([]);
  const [showTrash, setShowTrash] = useState(false);
  const [notes, setNotes] = useState<GuideNote[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [detail, setDetail] = useState<NoteDetail | null>(null);
  const [editing, setEditing] = useState(false);
  const [initial, setInitial] = useState<NoteDetail>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const fail = (e: unknown) => setError((e as Error).message);
  async function load() {
    const [list, cloud, removed, removedDrafts] = await Promise.allSettled([notesApi.list(), notesApi.drafts(), notesApi.trash(), notesApi.draftTrash()]);
    if (list.status === 'fulfilled') setNotes(list.value);
    if (removed.status === 'fulfilled') setTrash(removed.value);
    if (removedDrafts.status === 'fulfilled') setDraftTrash(removedDrafts.value);
    const local = user ? cachedDrafts(user.id) : [];
    setDrafts(cloud.status === 'fulfilled' && removedDrafts.status === 'fulfilled'
      ? mergeDrafts(cloud.value, local, removedDrafts.value, user?.id) : local);
    const failed = [list, cloud, removed, removedDrafts].find(value => value.status === 'rejected');
    if (failed?.status === 'rejected') fail(failed.reason);
  }
  function editDraft(value: NoteDraft) { setDraft(value); setInitial(undefined); setDetail(null); setEditing(true); setError(''); }
  async function closeEditor() { setEditing(false); setDraft(undefined); try { await load(); } catch (e) { fail(e); } }
  async function restoreNote(id: number) { setBusy(true); try { await notesApi.restore(id); await load(); } catch (e) { fail(e); } finally { setBusy(false); } }
  async function restoreDraft(id: string) { setBusy(true); try { await notesApi.restoreDraft(id); await load(); } catch (e) { fail(e); } finally { setBusy(false); } }
  async function removeDraft(value: NoteDraft) {
    if (!confirm('删除这份草稿？服务器保留 7 天，可从回收站恢复。')) return;
    setBusy(true);
    try {
      // Local-only recovery copies must reach the server before entering its recycle bin.
      const saved = value.version === undefined ? await notesApi.saveDraft({ ...value, attachmentIds: value.attachments.map(f => f.id) }) : value;
      await notesApi.deleteDraft(saved.id);
      if (user) forgetDraft(user.id, saved.id);
      await load();
    } catch (e) { fail(e); } finally { setBusy(false); }
  }
  useEffect(() => { load().catch(fail).finally(() => setLoading(false)); }, []);
  async function open(id: number) {
    setBusy(true); setError('');
    try { setDetail(await notesApi.detail(id)); } catch (e) { fail(e); } finally { setBusy(false); }
  }
  async function saved(note: GuideNote) {
    setEditing(false); setDraft(undefined); setDetail(null); setBusy(true); setError('');
    try { await load(); setDetail(await notesApi.detail(note.id)); } catch (e) { fail(e); } finally { setBusy(false); }
  }
  async function remove() {
    if (!detail || !confirm('将这张攻略移入回收站？正文和附件保留 7 天，期间可恢复。')) return;
    setBusy(true);
    try { await notesApi.remove(detail.note.id); setDetail(null); await load(); } catch (e) { fail(e); } finally { setBusy(false); }
  }
  async function pin() {
    if (!detail) return;
    setBusy(true);
    try { const note = await notesApi.pin(detail.note); setDetail({ ...detail, note }); await load(); } catch (e) { fail(e); } finally { setBusy(false); }
  }
  const filtered = notes.filter(note => (note.title + ' ' + noteSummary(note.content)).toLowerCase().includes(query.toLowerCase()));
  return <div className="notes-page">
    <header className="notes-header"><div><span className="notes-eyebrow">SEASON FIELD NOTES</span><h1>活动攻略与TIPS</h1><p className="muted">攻略、活动提醒与容易错过的小细节，一起记在这里。</p></div><button className="btn primary" disabled={busy} onClick={() => { setInitial(undefined); setDraft(undefined); setError(''); setEditing(true); }}>＋ 发布便签</button></header>
    {error && <p className="note-error" role="alert">{error}</p>}
    <div className="toolbar">
      <span className="muted">我的草稿 {drafts.length} 份 · 仅自己可见</span>
      <button className="btn small" disabled={busy} onClick={() => setShowTrash(value => !value)}>{showTrash ? '收起回收站' : `回收站（${trash.length + draftTrash.length}）`}</button>
    </div>
    {drafts.length > 0 && <section><h2 className="notes-section">我的草稿</h2><div className="notes-grid">{drafts.map(value => <article className="note-card" key={value.id}>
      <h3>{value.title || '未命名草稿'}</h3><p>{noteSummary(value.content) || '尚未填写正文'}</p>
      <p className="muted">{new Date(value.updatedAt).toLocaleString('zh-CN')}</p>
      <div className="toolbar"><button className="btn small" disabled={busy} onClick={() => editDraft(value)}>继续编辑</button><button className="btn small danger" disabled={busy} onClick={() => removeDraft(value)}>删除草稿</button></div>
    </article>)}</div></section>}
    {showTrash && <section className="card"><h2>回收站</h2><p className="muted">删除后保留 7 天，到期清理正文及不再被其他攻略或草稿使用的附件。草稿仅自己可见。</p>
      {trash.map(value => <div className="toolbar" key={`note-${value.id}`}><span>攻略：{value.title}</span><span className="muted">删除于 {new Date(value.deletedAt!).toLocaleString('zh-CN')}</span><button className="btn small" disabled={busy} onClick={() => restoreNote(value.id)}>恢复攻略</button></div>)}
      {draftTrash.map(value => <div className="toolbar" key={value.id}><span>草稿：{value.title || '未命名草稿'}</span><span className="muted">删除于 {new Date(value.deletedAt!).toLocaleString('zh-CN')}</span><button className="btn small" disabled={busy} onClick={() => restoreDraft(value.id)}>恢复草稿</button></div>)}
      {!trash.length && !draftTrash.length && <p className="muted">回收站为空</p>}
    </section>}
    <input type="text" className="notes-search" aria-label="搜索便签" placeholder="搜索标题或攻略内容…" value={query} onChange={e => setQuery(e.target.value)} />
    {loading ? <p>正在加载便签…</p> : filtered.length === 0 ? <div className="notes-empty"><h2>{query ? '没有找到相关便签' : '新赛季，从第一张便签开始'}</h2><p>{query ? '换个关键词试试。' : '发布活动攻略、贴上图片，让大家少错过一份奖励。'}</p></div> : [true, false].map(pinned => {
      const group = filtered.filter(note => note.pinned === pinned);
      return group.length > 0 && <section key={String(pinned)}><h2 className="notes-section">{pinned ? '📌 置顶必看' : '全部便签'} <small>{group.length}</small></h2><div className="notes-grid">{group.map(note => <button disabled={busy} className={`note-card ${note.pinned ? 'is-pinned' : ''}`} key={note.id} onClick={() => open(note.id)}><span className="note-tag">{note.pinned ? '置顶 · 重点提醒' : '攻略与提示'}</span><h3>{note.title}</h3><p>{noteSummary(note.content)}</p><footer><time>{new Date(note.updatedAt).toLocaleString('zh-CN')}</time><span>查看详情 ↗</span></footer></button>)}</div></section>;
    })}
    {editing && <NoteEditor key={draft?.id ?? initial?.note.id ?? 'new'} initial={initial} draft={draft} onSaved={saved} onClose={() => { void closeEditor(); }} />}
    {detail && !editing && <Modal title={detail.note.title} onClose={() => { if (!busy) setDetail(null); }} footer={<>
      <button className="btn" disabled={busy} onClick={pin}>{detail.note.pinned ? '取消置顶' : '置顶'}</button>
      <button className="btn" disabled={busy} onClick={() => { const existing = drafts.find(value => value.noteId === detail.note.id); setDraft(existing); setInitial(detail); setEditing(true); }}>编辑</button>
      <button className="btn" disabled={busy} onClick={remove}>删除</button>
      <button className="btn primary" disabled={busy} onClick={() => setDetail(null)}>关闭</button>
    </>}><p className="muted">{detail.note.pinned ? '📌 置顶 · ' : ''}更新于 {new Date(detail.note.updatedAt).toLocaleString('zh-CN')}</p><NoteBody detail={detail} /></Modal>}
  </div>;
}
