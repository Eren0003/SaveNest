'use client';

import { useParams, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { api, ApiError, type BookmarkPatch } from '@/lib/api';
import type { Bookmark, Collection, Tag } from '@/lib/types';
import { GROUP_IDS, GROUP_LABELS, PROGRESS_LABELS, progressOptions } from '@/lib/labels';
import { GroupBadge, PlatformBadge } from '@/components/Badges';
import { useApp } from '@/components/AppContext';
import { TagInput } from '@/components/TagInput';
import { CollectionPicker } from '@/components/CollectionPicker';
import { isEnriching, metadataExplanation, metadataLabel } from '@/lib/metadata';

export default function BookmarkDetailPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { bump, announce } = useApp();
  const [b, setB] = useState<Bookmark | null>(null);
  const [loadError, setLoadError] = useState('');
  const [form, setForm] = useState({ title: '', description: '', notes: '', group: '', progress: 'none' });
  const [tagNames, setTagNames] = useState<string[]>([]);
  const [collectionIds, setCollectionIds] = useState<string[]>([]);
  const [allTags, setAllTags] = useState<Tag[]>([]);
  const [allCollections, setAllCollections] = useState<Collection[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const [imgFailed, setImgFailed] = useState(false);

  function fill(x: Bookmark) {
    setB(x);
    setTagNames(x.tags.map((t) => t.name));
    setCollectionIds(x.collections.map((c) => c.id));
    setForm({
      title: x.title ?? '', description: x.description ?? '', notes: x.notes ?? '',
      group: x.groupOverridden ? x.groupId : '', progress: x.progressState,
    });
  }

  useEffect(() => {
    api.tags().then(setAllTags).catch(() => {});
    api.collections().then(setAllCollections).catch(() => {});
  }, []);

  useEffect(() => {
    api.getBookmark(id).then(fill).catch((e) =>
      setLoadError(e instanceof ApiError && e.status === 404 ? 'Bookmark not found.' : 'Could not load bookmark.'));
  }, [id]);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!b) return;
    setSaving(true);
    setError('');
    // Only send changed text fields: sending an unchanged title would mark it user-edited and
    // stop enrichment from ever filling it in.
    const patch: BookmarkPatch = { progressState: form.progress, tagNames, collectionIds };
    if (form.title !== (b.title ?? '')) patch.title = form.title;
    if (form.description !== (b.description ?? '')) patch.description = form.description;
    if (form.notes !== (b.notes ?? '')) patch.notes = form.notes;
    if (form.group) patch.groupId = form.group;
    else if (b.groupOverridden) patch.clearGroupOverride = true;
    try {
      fill(await api.updateBookmark(b.id, patch));
      bump();
      announce('Changes saved');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  // While enrichment runs, refresh only the server-side view (badges/preview), never the form inputs.
  const enriching = b ? isEnriching(b) : false;
  useEffect(() => {
    if (!enriching) return;
    let tries = 0;
    const timer = setInterval(() => {
      if (++tries > 40) { clearInterval(timer); return; }
      api.getBookmark(id).then((x) => {
        setB(x);
        if (!isEnriching(x)) {
          // Fill fields the user has not touched in this form with fetched values.
          setForm((f) => ({
            ...f,
            title: f.title === '' || f.title === x.host ? x.title ?? '' : f.title,
            description: f.description === '' ? x.description ?? '' : f.description,
          }));
          announce(metadataLabel(x));
        }
      }).catch(() => {});
    }, 3000);
    return () => clearInterval(timer);
  }, [enriching, id, announce]);

  async function refresh() {
    if (!b) return;
    setRefreshing(true);
    setError('');
    try {
      setB(await api.refreshBookmark(b.id));
      announce('Metadata refresh queued');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not refresh metadata');
    } finally {
      setRefreshing(false);
    }
  }

  async function trash() {
    if (!b) return;
    try {
      await api.trashBookmark(b.id);
      bump();
      announce('Moved to trash');
      router.push('/');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not move to trash');
    }
  }

  async function restore() {
    if (!b) return;
    try {
      setB(await api.restoreBookmark(b.id));
      bump();
      announce('Restored from trash');
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'duplicate_bookmark'
        ? 'Cannot restore: this link is already saved.'
        : err instanceof ApiError ? err.message : 'Could not restore');
    }
  }

  if (loadError) return <p role="alert">{loadError}</p>;
  if (!b) return <p role="status">Loading…</p>;

  const fmt = (s: string | null) => (s ? new Date(s).toLocaleString() : 'Never');
  const progressChoices = progressOptions(b.contentType);
  const previewSrc = api.previewSrc(b);

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <h1 className="break-words text-2xl font-bold">{b.title}</h1>
        <a href={b.url} target="_blank" rel="noopener noreferrer nofollow" className="break-all text-sm text-indigo-700 underline dark:text-indigo-300"
          onClick={() => { api.openBookmark(b.id).then(setB).catch(() => {}); }}>
          {b.url}<span className="sr-only"> (opens in new tab)</span>
        </a>
        <div className="mt-2 flex flex-wrap gap-1">
          <GroupBadge id={b.groupId} />
          <PlatformBadge platform={b.platform} />
          <span className="badge">{b.contentType}{b.subtype ? ` · ${b.subtype}` : ''}</span>
        </div>
      </div>

      <section aria-labelledby="preview-h" className="card overflow-hidden text-sm">
        {previewSrc && !imgFailed && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={previewSrc} alt={`Preview image for ${b.title}`} loading="lazy" decoding="async"
            onError={() => setImgFailed(true)} className="aspect-[16/9] w-full bg-slate-100 object-cover dark:bg-slate-800" />
        )}
        <div className="space-y-2 p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 id="preview-h" className="font-semibold">Preview</h2>
            <span role="status" className="badge">{metadataLabel(b)}</span>
          </div>
          {b.siteName && <p className="text-xs text-slate-500">{b.siteName}</p>}
          {metadataExplanation(b) && <p className="text-slate-600 dark:text-slate-400">{metadataExplanation(b)}</p>}
          {b.resolvedUrl && b.resolvedUrl !== b.url && (
            <p className="break-all text-xs text-slate-500">Resolves to: {b.resolvedUrl}</p>
          )}
          <p className="text-xs text-slate-500">
            {b.metadataFetchedAt ? `Fetched ${fmt(b.metadataFetchedAt)}` : 'Not fetched yet'}
            {(b.titleEdited || b.descriptionEdited) && ' · your edited title/description are kept on refresh'}
          </p>
          {!b.deletedAt && (
            <button type="button" className="btn-ghost border border-slate-300 dark:border-slate-700"
              onClick={refresh} disabled={refreshing || enriching}>
              {refreshing ? 'Queuing…' : enriching ? 'Fetching…' : 'Retry metadata'}
            </button>
          )}
        </div>
      </section>

      <section aria-labelledby="why-h" className="card p-4 text-sm">
        <h2 id="why-h" className="mb-1 font-semibold">Why this group?</h2>
        <p>{b.classification.reason}</p>
        <p className="mt-1 text-xs text-slate-500">
          Rule {b.classification.ruleId} v{b.classification.ruleVersion} · {b.classification.confidence} confidence ·
          detected as {GROUP_LABELS[b.detectedGroupId]}{b.groupOverridden ? ' (overridden by you)' : ''}
        </p>
      </section>

      <form onSubmit={save} className="space-y-4">
        <div>
          <label htmlFor="d-title" className="label">Title</label>
          <input id="d-title" className="input" maxLength={500} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
        </div>
        <div>
          <label htmlFor="d-desc" className="label">Description</label>
          <textarea id="d-desc" className="input" rows={3} maxLength={5000} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
        </div>
        <div>
          <label htmlFor="d-notes" className="label">Notes</label>
          <textarea id="d-notes" className="input" rows={4} maxLength={10000} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor="d-group" className="label">Group</label>
            <select id="d-group" className="input" value={form.group} onChange={(e) => setForm({ ...form, group: e.target.value })}>
              <option value="">Automatic ({GROUP_LABELS[b.detectedGroupId]})</option>
              {GROUP_IDS.map((g) => <option key={g} value={g}>{GROUP_LABELS[g]}</option>)}
            </select>
          </div>
          {progressChoices.length > 1 && (
            <div>
              <label htmlFor="d-progress" className="label">Progress</label>
              <select id="d-progress" className="input" value={form.progress} onChange={(e) => setForm({ ...form, progress: e.target.value })}>
                {progressChoices.map((p) => <option key={p} value={p}>{PROGRESS_LABELS[p]}</option>)}
              </select>
            </div>
          )}
        </div>
        <div>
          <label htmlFor="d-tags" className="label">Tags</label>
          <TagInput id="d-tags" value={tagNames} onChange={setTagNames} suggestions={allTags.map((t) => t.name)} />
        </div>
        <CollectionPicker
          collections={allCollections} selected={collectionIds} onChange={setCollectionIds}
          onCreated={(c) => setAllCollections((x) => [...x, c])}
        />
        {error && <p role="alert" className="text-sm text-red-700 dark:text-red-400">{error}</p>}
        <div className="flex flex-wrap gap-2">
          <button type="submit" className="btn-primary" disabled={saving}>{saving ? 'Saving…' : 'Save changes'}</button>
          {!b.deletedAt && (
            <button type="button" className="btn-ghost text-red-700 dark:text-red-400" onClick={trash}>Move to trash</button>
          )}
          {b.deletedAt && (
            <button type="button" className="btn-ghost" onClick={restore}>Restore from trash</button>
          )}
        </div>
      </form>

      <dl className="grid grid-cols-2 gap-2 text-sm text-slate-600 dark:text-slate-400">
        <dt>Saved</dt><dd>{fmt(b.createdAt)}</dd>
        <dt>Updated</dt><dd>{fmt(b.updatedAt)}</dd>
        <dt>Last opened</dt><dd>{fmt(b.lastOpenedAt)}</dd>
        <dt>Times opened</dt><dd>{b.openCount}</dd>
      </dl>
    </div>
  );
}
