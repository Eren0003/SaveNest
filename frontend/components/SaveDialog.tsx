'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import type { Bookmark, ClassifyResult, Collection, GroupId, Tag } from '@/lib/types';
import { GROUP_IDS, GROUP_LABELS, PROGRESS_LABELS, progressOptions } from '@/lib/labels';
import { GroupBadge, PlatformBadge } from './Badges';
import { TagInput } from './TagInput';
import { CollectionPicker } from './CollectionPicker';

type Result =
  | { kind: 'saved'; bookmark: Bookmark }
  | { kind: 'duplicate'; existingId: string | null }
  | { kind: 'error'; message: string };

/** Native <dialog> gives focus trapping, Escape-to-close and focus restoration. */
export function SaveDialog({ open, onClose, onSaved }: {
  open: boolean; onClose: () => void; onSaved: (b: Bookmark) => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const [url, setUrl] = useState('');
  const [notes, setNotes] = useState('');
  const [groupId, setGroupId] = useState('');
  const [progress, setProgress] = useState('none');
  const [preview, setPreview] = useState<ClassifyResult | null>(null);
  const [previewError, setPreviewError] = useState('');
  const [tagNames, setTagNames] = useState<string[]>([]);
  const [collectionIds, setCollectionIds] = useState<string[]>([]);
  const [allTags, setAllTags] = useState<Tag[]>([]);
  const [allCollections, setAllCollections] = useState<Collection[]>([]);
  const [saving, setSaving] = useState(false);
  const [result, setResult] = useState<Result | null>(null);

  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);

  useEffect(() => {
    if (!open) return;
    api.tags().then(setAllTags).catch(() => {});
    api.collections().then(setAllCollections).catch(() => {});
  }, [open]);

  // Debounced rule-based preview; stale requests are aborted.
  useEffect(() => {
    setPreview(null);
    setPreviewError('');
    const value = url.trim();
    if (!value) return;
    const ctrl = new AbortController();
    const t = setTimeout(() => {
      api.classify(value, ctrl.signal)
        .then((r) => { setPreview(r); setProgress('none'); })
        .catch((e) => {
          if ((e as Error).name === 'AbortError') return;
          setPreviewError(e instanceof ApiError ? e.message : 'Could not check this URL');
        });
    }, 300);
    return () => { clearTimeout(t); ctrl.abort(); };
  }, [url]);

  function reset() {
    setUrl(''); setNotes(''); setGroupId(''); setProgress('none'); setResult(null); setTagNames([]); setCollectionIds([]);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setResult(null);
    try {
      const b = await api.createBookmark({
        url: url.trim(),
        notes: notes.trim() || undefined,
        groupId: groupId || undefined,
        progressState: progress !== 'none' ? progress : undefined,
        tagNames: tagNames.length ? tagNames : undefined,
        collectionIds: collectionIds.length ? collectionIds : undefined,
      });
      onSaved(b);
      reset();
      setResult({ kind: 'saved', bookmark: b });
    } catch (err) {
      // User-entered text is intentionally preserved on failure.
      if (err instanceof ApiError && err.status === 409) {
        setResult({ kind: 'duplicate', existingId: (err.details?.existingId as string) ?? null });
      } else {
        setResult({ kind: 'error', message: err instanceof ApiError ? err.message : 'Save failed' });
      }
    } finally {
      setSaving(false);
    }
  }

  const c = preview?.classification;
  const effectiveGroup = (groupId || c?.groupId) as GroupId | undefined;
  const progressChoices = c ? progressOptions(c.contentType) : ['none'];

  return (
    <dialog
      ref={ref}
      onClose={() => { setResult(null); onClose(); }}
      aria-labelledby="save-title"
      className="w-[min(100%-2rem,32rem)] rounded-lg bg-white p-0 text-slate-900 dark:bg-slate-900 dark:text-slate-100"
    >
      <form onSubmit={submit} className="flex flex-col gap-4 p-5">
        <div className="flex items-center justify-between">
          <h2 id="save-title" className="text-lg font-semibold">Save link</h2>
          <button type="button" className="btn-ghost px-2" onClick={onClose} aria-label="Close">✕</button>
        </div>

        <div>
          <label htmlFor="save-url" className="label">URL</label>
          <input
            id="save-url" className="input" type="text" inputMode="url" autoComplete="off" required autoFocus
            maxLength={2100} value={url} onChange={(e) => setUrl(e.target.value)}
            placeholder="https://www.youtube.com/watch?v=…" aria-describedby="save-detect"
          />
          <div id="save-detect" aria-live="polite" className="mt-2 min-h-[1.5rem] text-sm">
            {c && effectiveGroup && (
              <div className="flex flex-wrap items-center gap-1">
                <span className="text-slate-500">Detected:</span>
                <GroupBadge id={effectiveGroup} />
                <PlatformBadge platform={c.platform} />
                <span className="badge">{c.contentType}{c.subtype ? ` · ${c.subtype}` : ''}</span>
              </div>
            )}
            {preview?.schemeAdded && <p className="mt-1 text-xs text-slate-500">Interpreted as {preview.interpretedUrl}</p>}
            {previewError && <p className="text-red-700 dark:text-red-400">{previewError}</p>}
          </div>
        </div>

        <div>
          <label htmlFor="save-group" className="label">Group (optional override)</label>
          <select id="save-group" className="input" value={groupId} onChange={(e) => setGroupId(e.target.value)}>
            <option value="">Automatic{c ? ` (${GROUP_LABELS[c.groupId]})` : ''}</option>
            {GROUP_IDS.map((g) => <option key={g} value={g}>{GROUP_LABELS[g]}</option>)}
          </select>
        </div>

        {progressChoices.length > 1 && (
          <div>
            <label htmlFor="save-progress" className="label">Progress</label>
            <select id="save-progress" className="input" value={progress} onChange={(e) => setProgress(e.target.value)}>
              {progressChoices.map((p) => <option key={p} value={p}>{PROGRESS_LABELS[p as keyof typeof PROGRESS_LABELS]}</option>)}
            </select>
          </div>
        )}

        <div>
          <label htmlFor="save-tags" className="label">Tags (optional)</label>
          <TagInput id="save-tags" value={tagNames} onChange={setTagNames} suggestions={allTags.map((t) => t.name)} />
        </div>

        <CollectionPicker
          collections={allCollections} selected={collectionIds} onChange={setCollectionIds}
          onCreated={(c) => setAllCollections((x) => [...x, c])}
        />

        <div>
          <label htmlFor="save-notes" className="label">Notes (optional)</label>
          <textarea id="save-notes" className="input" rows={3} maxLength={10000} value={notes} onChange={(e) => setNotes(e.target.value)} />
        </div>

        <div role="status" aria-live="polite" className="text-sm">
          {result?.kind === 'saved' && (
            <p className="text-green-700 dark:text-green-400">
              Saved. Preview is pending.{' '}
              <Link className="underline" href={`/bookmarks/${result.bookmark.id}`} onClick={onClose}>View bookmark</Link>
            </p>
          )}
          {result?.kind === 'duplicate' && (
            <p className="text-amber-700 dark:text-amber-400">
              This link is already in your library.{' '}
              {result.existingId && (
                <Link className="underline" href={`/bookmarks/${result.existingId}`} onClick={onClose}>View existing</Link>
              )}
            </p>
          )}
          {result?.kind === 'error' && <p role="alert" className="text-red-700 dark:text-red-400">{result.message}</p>}
        </div>

        <div className="flex justify-end gap-2">
          <button type="button" className="btn-ghost" onClick={onClose}>Close</button>
          <button type="submit" className="btn-primary" disabled={saving || !url.trim()}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </dialog>
  );
}
