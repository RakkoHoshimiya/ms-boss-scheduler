export interface DownloadProgress { loaded: number; total: number }
type Listener = (progress: DownloadProgress) => void;
type Loader = (signal: AbortSignal, progress: Listener) => Promise<Blob>;
interface Entry {
  promise: Promise<Blob>; controller: AbortController; listeners: Set<Listener>;
  progress: DownloadProgress; bytes: number; expires: number;
}

/** Session memory only. Never persist authenticated images to browser storage. */
export function createNoteBlobCache(maxBytes = 16 * 1024 * 1024, ttl = 5 * 60 * 1000) {
  const entries = new Map<string, Entry>();
  let bytes = 0;
  function remove(key: string, entry: Entry) {
    if (entries.get(key) !== entry) return;
    entries.delete(key); bytes -= entry.bytes;
  }
  return {
    clear() {
      for (const entry of entries.values()) entry.controller.abort();
      entries.clear(); bytes = 0;
    },
    get(key: string, loader: Loader, listener: Listener = () => {}) {
      let entry = entries.get(key);
      if (entry && entry.expires <= Date.now()) { remove(key, entry); entry = undefined; }
      if (!entry) {
        const next: Entry = { promise: undefined as unknown as Promise<Blob>, controller: new AbortController(),
          listeners: new Set(), progress: { loaded: 0, total: 0 }, bytes: 0, expires: Infinity };
        entries.set(key, next);
        next.promise = Promise.resolve().then(() => loader(next.controller.signal, progress => {
          next.progress = progress;
          for (const notify of next.listeners) notify(progress);
        })).then(blob => {
          if (next.controller.signal.aborted) throw new DOMException('会话已结束', 'AbortError');
          if (entries.get(key) === next) {
            next.bytes = blob.size; next.expires = Date.now() + ttl; bytes += blob.size;
            for (const [id, candidate] of entries) {
              if (bytes <= maxBytes) break;
              if (candidate.expires !== Infinity) remove(id, candidate);
            }
          }
          return blob;
        }).catch(error => { remove(key, next); throw error; });
        entry = next;
      }
      entries.delete(key); entries.set(key, entry); // LRU, including a repeated completed request.
      entry.listeners.add(listener); listener(entry.progress);
      const current = entry;
      return { promise: entry.promise, unsubscribe: () => current.listeners.delete(listener) };
    },
  };
}
