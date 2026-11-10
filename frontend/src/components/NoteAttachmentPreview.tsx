import { useEffect, useState } from 'react';
import { notesApi, type NoteAttachment } from '../api/client';

export default function NoteAttachmentPreview({ file, compact = false }: { file: NoteAttachment; compact?: boolean }) {
  const [url, setUrl] = useState('');
  const [error, setError] = useState('');
  const image = file.mediaType.startsWith('image/');
  useEffect(() => {
    if (!image) return;
    let active = true;
    let objectUrl = '';
    notesApi.file(file.id).then(blob => {
      objectUrl = URL.createObjectURL(blob);
      if (active) setUrl(objectUrl); else URL.revokeObjectURL(objectUrl);
    }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [file.id, image]);
  async function download() {
    try {
      const blob = await notesApi.file(file.id);
      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = objectUrl; link.download = file.name; link.click();
      setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    } catch (e) { setError((e as Error).message); }
  }
  return <div className={`note-attachment ${compact ? 'is-compact' : ''}`}>
    {image && !url && !error && <p className="muted">图片加载中…</p>}
    {url && <a href={url} target="_blank" rel="noreferrer"><img src={url} alt={file.name} /></a>}
    <button type="button" className="btn small" onClick={download}>{file.name} · {(file.size / 1024).toFixed(0)} KB ↓</button>
    {error && <p role="alert" className="note-error">{error}</p>}
  </div>;
}
