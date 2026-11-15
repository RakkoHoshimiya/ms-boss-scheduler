import { useEffect, useState } from 'react';
import { notesApi, type NoteAttachment } from '../api/client';
import type { DownloadProgress } from '../features/note-blob-cache';

function useImage(id: string, enabled: boolean, preview: boolean, attempt: number) {
  const [state, setState] = useState({ id: '', url: '', error: '', loaded: 0, total: 0 });
  useEffect(() => {
    if (!enabled) return;
    let active = true, objectUrl = '';
    setState({ id, url: '', error: '', loaded: 0, total: 0 });
    const load = notesApi.image(id, preview, progress => {
      if (active) setState(current => ({ ...current, ...progress }));
    });
    load.promise.then(blob => {
      if (!active) return;
      objectUrl = URL.createObjectURL(blob);
      setState(current => ({ ...current, url: objectUrl }));
    }).catch(error => { if (active) setState(current => ({ ...current, error: (error as Error).message })); });
    return () => { active = false; load.unsubscribe(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [id, enabled, preview, attempt]);
  return state.id === id ? state : { id, url: '', error: '', loaded: 0, total: 0 };
}

function Progress({ value, label }: { value: DownloadProgress; label: string }) {
  const percent = value.total > 0 ? Math.min(100, Math.floor(value.loaded / value.total * 100)) : undefined;
  return <div className="note-image-progress" role="status">
    <span>{label} {percent === undefined ? `${Math.round(value.loaded / 1024)} KB` : `${percent}%`}</span>
    <progress aria-label={label} max={100} value={percent} />
  </div>;
}

export default function NoteAttachmentPreview({ file, compact = false }: { file: NoteAttachment; compact?: boolean }) {
  const image = file.mediaType.startsWith('image/');
  const [originalId, setOriginalId] = useState('');
  const [previewAttempt, setPreviewAttempt] = useState(0);
  const [originalAttempt, setOriginalAttempt] = useState(0);
  const [downloadProgress, setDownloadProgress] = useState<DownloadProgress | null>(null);
  const [error, setError] = useState('');
  const preview = useImage(file.id, image, true, previewAttempt);
  const original = useImage(file.id, image && originalId === file.id, false, originalAttempt);
  const url = original.url || preview.url;
  const loadingOriginal = originalId === file.id && !original.url;
  const pending = loadingOriginal ? original : !preview.url ? preview : null;
  const failure = (loadingOriginal ? original.error : preview.error) || error;
  async function download() {
    setError(''); setDownloadProgress({ loaded: 0, total: file.size });
    const load = notesApi.image(file.id, false, setDownloadProgress);
    try {
      const blob = await load.promise;
      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = objectUrl; link.download = file.name; link.click();
      setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    } catch (e) { setError((e as Error).message); }
    finally { load.unsubscribe(); setDownloadProgress(null); }
  }
  return <div className={`note-attachment ${compact ? 'is-compact' : ''}`}>
    {url && <a href={url} target="_blank" rel="noreferrer" title={original.url ? '在新窗口查看原图' : '在新窗口查看预览图'}><img src={url} alt={file.name} /></a>}
    <div className="note-attachment-actions">
      {image && !original.url && <button type="button" className="btn small" disabled={loadingOriginal && !original.error} onClick={() => { setOriginalId(file.id); setOriginalAttempt(value => value + 1); }}>{loadingOriginal && !original.error ? '原图加载中…' : '查看原图'}</button>}
      {original.url && <a className="btn small" href={original.url} target="_blank" rel="noreferrer">打开原图 ↗</a>}
      <button type="button" className="btn small" disabled={downloadProgress !== null} onClick={download}>{file.name} · {(file.size / 1024).toFixed(0)} KB ↓</button>
    </div>
    {image && pending && !failure && <Progress value={pending} label={loadingOriginal ? '正在加载原图' : '正在加载预览图'} />}
    {downloadProgress && <Progress value={downloadProgress} label="正在下载附件" />}
    {failure && <div className="note-error" role="alert">{failure} {image && <button type="button" className="btn small" onClick={() => { setError(''); if (loadingOriginal) setOriginalAttempt(value => value + 1); else setPreviewAttempt(value => value + 1); }}>重试图片</button>}</div>}
  </div>;
}
