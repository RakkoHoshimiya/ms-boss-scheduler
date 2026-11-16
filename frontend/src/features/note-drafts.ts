import type { NoteDraft } from '../api/client';

export type RecoveryDraft = NoteDraft & { pendingLocalChanges?: boolean };
export function draftSignature(draft: NoteDraft): string {
  return JSON.stringify({ id: draft.id, noteId: draft.noteId, sourceVersion: draft.sourceVersion,
    title: draft.title, content: draft.content, pinned: draft.pinned, attachmentIds: draft.attachments.map(file => file.id) });
}

const prefix = (userId: number) => `gmsr.note-draft.${userId}.`;
export function cacheDraft(userId: number, draft: RecoveryDraft): boolean {
  try { localStorage.setItem(prefix(userId) + draft.id, JSON.stringify(draft)); return true; }
  catch { return false; }
}
export function forgetDraft(userId: number, id: string) {
  try { localStorage.removeItem(prefix(userId) + id); } catch { /* Storage can be unavailable. */ }
}
export function cachedDrafts(userId: number): RecoveryDraft[] {
  const result: RecoveryDraft[] = [];
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key?.startsWith(prefix(userId))) continue;
      try {
        const d = JSON.parse(localStorage.getItem(key) ?? 'null') as RecoveryDraft;
        if (d && typeof d.id === 'string' && key === prefix(userId) + d.id && typeof d.title === 'string' && typeof d.content === 'string' && Array.isArray(d.attachments)) result.push(d);
      } catch { /* Ignore incomplete local entries. */ }
    }
  } catch { /* Cloud drafts still work with local storage disabled. */ }
  return result;
}
export function mergeDrafts(cloud: NoteDraft[], local: RecoveryDraft[], deleted: NoteDraft[], userId?: number): RecoveryDraft[] {
  const tombstones = new Set(deleted.map(d => d.id));
  const merged = new Map<string, RecoveryDraft>(cloud.map(d => [d.id, d]));
  function recoveryCopy(d: RecoveryDraft) {
    const copy = { ...d, id: crypto.randomUUID(), version: undefined, pendingLocalChanges: true, title: d.title.slice(0, 110) + '（恢复副本）' };
    if (userId !== undefined && cacheDraft(userId, copy)) forgetDraft(userId, d.id);
    merged.set(copy.id, copy);
  }
  for (const d of local) {
    if (tombstones.has(d.id)) { if (userId !== undefined) forgetDraft(userId, d.id); continue; }
    const remote = merged.get(d.id);
    // A stale tab must not silently overwrite a newer cloud revision.
    if (!remote) {
      // A known cloud draft missing from both lists may already be published or purged.
      if (d.version === undefined) merged.set(d.id, d);
      else if (d.pendingLocalChanges) recoveryCopy(d);
      else if (userId !== undefined) forgetDraft(userId, d.id);
    } else if (d.pendingLocalChanges || (d.pendingLocalChanges === undefined && Date.parse(d.updatedAt) > Date.parse(remote.updatedAt))) {
      // Explicit dirtiness survives a slow old save and clock skew between browser and server.
      if (d.version === remote.version) merged.set(d.id, { ...d, pendingLocalChanges: true });
      else recoveryCopy(d);
    }
  }
  return [...merged.values()].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
}
