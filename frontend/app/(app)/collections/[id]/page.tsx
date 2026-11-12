'use client';

import { useParams, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import type { Collection } from '@/lib/types';
import { LibraryView } from '@/components/LibraryView';
import { useApp } from '@/components/AppContext';

export default function CollectionPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { version, bump, announce } = useApp();
  const [c, setC] = useState<Collection | null | undefined>(undefined);
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    api.collections().then((all) => setC(all.find((x) => x.id === id) ?? null)).catch(() => setC(null));
  }, [id, version]);

  async function rename(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    try {
      await api.updateCollection(id, { name });
      setEditing(false); bump(); announce('Collection renamed');
    } catch (err) { setError(err instanceof ApiError ? err.message : 'Rename failed'); }
  }

  async function remove() {
    if (!c || !window.confirm(`Delete collection "${c.name}"? Its bookmarks are kept.`)) return;
    try {
      await api.deleteCollection(id);
      bump(); announce('Collection deleted'); router.push('/all');
    } catch (err) { setError(err instanceof ApiError ? err.message : 'Delete failed'); }
  }

  if (c === undefined) return <p role="status">Loading…</p>;
  if (c === null) return <p role="alert">Collection not found.</p>;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        {editing ? (
          <form onSubmit={rename} className="flex gap-2">
            <label htmlFor="c-name" className="sr-only">Collection name</label>
            <input id="c-name" className="input" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
            <button className="btn-primary" type="submit">Save</button>
            <button className="btn-ghost" type="button" onClick={() => setEditing(false)}>Cancel</button>
          </form>
        ) : (
          <>
            <h1 className="text-2xl font-bold">{c.name}</h1>
            <span className="text-sm text-slate-500">{c.count} {c.count === 1 ? 'bookmark' : 'bookmarks'}</span>
            <button className="btn-ghost" type="button" onClick={() => { setName(c.name); setEditing(true); }}>Rename</button>
            <button className="btn-ghost text-red-700 dark:text-red-400" type="button" onClick={remove}>Delete collection</button>
          </>
        )}
      </div>
      {error && <p role="alert" className="text-sm text-red-700 dark:text-red-400">{error}</p>}
      <LibraryView base={{ collection: id }} showCollection={false}
        empty={<p className="py-12 text-center text-slate-500">No bookmarks in this collection yet. Add some from a bookmark's page.</p>} />
    </div>
  );
}
