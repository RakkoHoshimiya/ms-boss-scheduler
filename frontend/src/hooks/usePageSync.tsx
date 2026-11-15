import { useEffect, useRef, useState } from 'react';

/** Fetch in the background, then commit only if the user has not started editing. */
export function usePageSync(prepare: () => Promise<() => void>, blocked = false, contextKey = '') {
  const current = useRef({ prepare, blocked }); current.current = { prepare, blocked };
  const [status, setStatus] = useState<'idle' | 'syncing' | 'deferred' | 'error'>('idle');
  const retry = useRef<() => void>(() => undefined);
  useEffect(() => {
    let disposed = false, running = false, deferred = false, epoch = 0, lastStart = 0;
    const protectedEdit = () => current.current.blocked || Boolean(document.querySelector('.modal'));
    const defer = () => { deferred = true; setStatus('deferred'); };
    async function sync(force = false) {
      if (disposed || document.visibilityState === 'hidden' || running) return;
      if (protectedEdit()) { defer(); return; }
      if (!force && Date.now() - lastStart < 1000) return;
      lastStart = Date.now(); running = true; deferred = false; setStatus('syncing');
      const started = epoch;
      try {
        const commit = await current.current.prepare();
        if (disposed) return;
        if (protectedEdit() || started !== epoch) { defer(); return; }
        commit(); setStatus('idle');
      } catch { if (!disposed) setStatus('error'); }
      finally { running = false; }
    }
    const wake = () => { void sync(); };
    const edit = () => { epoch++; };
    const observer = new MutationObserver(() => { if (deferred && !protectedEdit()) void sync(true); });
    observer.observe(document.body, { childList: true, subtree: true });
    window.addEventListener('focus', wake);
    window.addEventListener('online', wake);
    document.addEventListener('visibilitychange', wake);
    document.addEventListener('input', edit, true);
    document.addEventListener('dragstart', edit, true);
    retry.current = () => { void sync(true); };
    return () => {
      disposed = true; observer.disconnect();
      window.removeEventListener('focus', wake); window.removeEventListener('online', wake);
      document.removeEventListener('visibilitychange', wake); document.removeEventListener('input', edit, true); document.removeEventListener('dragstart', edit, true);
    };
  }, [contextKey]);
  useEffect(() => { if (!blocked && status === 'deferred') retry.current(); }, [blocked, status]);
  return status === 'idle' ? null : <p className="muted" role="status">
    {status === 'syncing' ? '正在同步最新排班…' : status === 'deferred' ? '正在编辑或操作，已暂缓自动同步；结束后会获取最新排班。' : '自动同步失败，当前内容已保留。'}
    {status === 'error' && <button className="btn small" onClick={() => retry.current()}>重试同步</button>}
  </p>;
}
