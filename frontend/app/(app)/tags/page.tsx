'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import type { Tag } from '@/lib/types';
import { useApp } from '@/components/AppContext';

export default function TagsPage() {
  const { version, bump, announce } = useApp();
  const [tags, setTags] = useState<Tag[] | null>(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<string | null>(null);
  const [name, setName] = useState('');

  useEffect(() => { api.tags().then(setTags).catch(() => setError('Could not load tags')); }, [version]);

  async function rename(t: Tag, e: React.FormEvent) {
    e.preventDefault();
    setError('');
    try {
      await api.renameTag(t.id, name);
      setEditing(null); bump(); announce('Tag renamed');
    } catch (err) {
      if (err instanceof ApiError && err.status === 409 && err.details?.existingId
        && window.confirm(`A tag named "${name}" already exists. Merge "${t.name}" into it?`)) {
        try { await api.mergeTag(t.id, err.details.existingId as string); setEditing(null); bump(); announce('Tags merged'); }
        catch (e2) { setError(e2 instanceof ApiError ? e2.message : 'Merge failed'); }
      } else if (!(err instanceof ApiError && err.status === 409)) {
        setError(err instanceof ApiError ? err.message : 'Rename failed');
      }
    }
  }

  async function remove(t: Tag) {
    if (!window.confirm(`Delete tag "${t.name}"? Bookmarks are kept.`)) return;
    try { await api.deleteTag(t.id); bump(); announce('Tag deleted'); }
    catch (err) { setError(err instanceof ApiError ? err.message : 'Delete failed'); }
  }

  return (
    <div className="mx-auto max-w-2xl space-y-4">
      <h1 className="text-2xl font-bold">Tags</h1>
      <p className="text-sm text-slate-500">Renaming a tag to an existing name offers to merge them. Bookmarks keep their tags.</p>
      {error && <p role="alert" className="text-sm text-red-700 dark:text-red-400">{error}</p>}
      {tags && tags.length === 0 && <p className="text-slate-500">No tags yet. Add tags when saving or editing a bookmark.</p>}
      <ul className="divide-y divide-slate-200 dark:divide-slate-800">
        {(tags ?? []).map((t) => (
          <li key={t.id} className="flex flex-wrap items-center gap-2 py-2">
            {editing === t.id ? (
              <form onSubmit={(e) => rename(t, e)} className="flex gap-2">
                <label htmlFor={`tn-${t.id}`} className="sr-only">New name for {t.name}</label>
                <input id={`tn-${t.id}`} className="input" value={name} maxLength={50} onChange={(e) => setName(e.target.value)} />
                <button className="btn-primary" type="submit">Save</button>
                <button className="btn-ghost" type="button" onClick={() => setEditing(null)}>Cancel</button>
              </form>
            ) : (
              <>
                <Link href={`/all?tag=${t.id}`} className="font-medium hover:underline">#{t.name}</Link>
                <span className="text-sm text-slate-500">{t.count}</span>
                <span className="ml-auto flex gap-1">
                  <button className="btn-ghost" type="button" onClick={() => { setEditing(t.id); setName(t.name); }}>Rename</button>
                  <button className="btn-ghost text-red-700 dark:text-red-400" type="button" onClick={() => remove(t)}>Delete</button>
                </span>
              </>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
