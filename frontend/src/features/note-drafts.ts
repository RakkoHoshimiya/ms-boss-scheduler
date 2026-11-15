import type { NoteDraft } from '../api/client';

const prefix = (userId: number) => `gmsr.note-draft.${userId}.`;
export function cacheDraft(userId: number, draft: NoteDraft): boolean {
  try { localStorage.setItem(prefix(userId) + draft.id, JSON.stringify(draft)); return true; }
  catch { return false; }
}
export function forgetDraft(userId: number, id: string) {
  try { localStorage.removeItem(prefix(userId) + id); } catch { /* Storage can be unavailable. */ }
}
export function cachedDrafts(userId: number): NoteDraft[] {
  const result: NoteDraft[] = [];
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key?.startsWith(prefix(userId))) continue;
      try {
        const d = JSON.parse(localStorage.getItem(key) ?? 'null') as NoteDraft;
        if (d && typeof d.id === 'string' && key === prefix(userId) + d.id && typeof d.title === 'string' && typeof d.content === 'string' && Array.isArray(d.attachments)) result.push(d);
      } catch { /* Ignore incomplete local entries. */ }
    }
  } catch { /* Cloud drafts still work with local storage disabled. */ }
  return result;
}
export function mergeDrafts(cloud: NoteDraft[], local: NoteDraft[], deleted: NoteDraft[], userId?: number): NoteDraft[] {
  const tombstones = new Set(deleted.map(d => d.id));
  const merged = new Map(cloud.map(d => [d.id, d]));
  for (const d of local) {
    if (tombstones.has(d.id)) { if (userId !== undefined) forgetDraft(userId, d.id); continue; }
    const remote = merged.get(d.id);
    // A stale tab must not silently overwrite a newer cloud revision.
    if (!remote) {
      // A known cloud draft missing from both lists may already be published or purged.
      if (d.version === undefined) merged.set(d.id, d);
      else if (userId !== undefined) forgetDraft(userId, d.id);
    } else if (d.updatedAt > remote.updatedAt) {
      if (d.version === remote.version) merged.set(d.id, d);
      else {
        const copy = { ...d, id: crypto.randomUUID(), version: undefined, title: d.title.slice(0, 110) + '（恢复副本）' };
        if (userId !== undefined && cacheDraft(userId, copy)) forgetDraft(userId, d.id);
        merged.set(copy.id, copy);
      }
    }
  }
  return [...merged.values()].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
}
