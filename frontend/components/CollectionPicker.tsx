'use client';

import { useState } from 'react';
import { api, ApiError } from '@/lib/api';
import type { Collection } from '@/lib/types';

/** Checkbox list of collections with inline creation. Controlled by selected ids. */
export function CollectionPicker({ collections, selected, onChange, onCreated }: {
  collections: Collection[]; selected: string[]; onChange: (ids: string[]) => void; onCreated: (c: Collection) => void;
}) {
  const [name, setName] = useState('');
  const [error, setError] = useState('');

  async function create() {
    const n = name.trim();
    if (!n) return;
    setError('');
    try {
      const c = await api.createCollection({ name: n });
      onCreated(c);
      onChange([...selected, c.id]);
      setName('');
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.details?.existingId) {
        const id = e.details.existingId as string;
        if (!selected.includes(id)) onChange([...selected, id]);
        setName('');
      } else setError(e instanceof ApiError ? e.message : 'Could not create collection');
    }
  }

  return (
    <fieldset>
      <legend className="label">Collections</legend>
      {collections.length === 0 && <p className="text-sm text-slate-500">No collections yet.</p>}
      <ul className="flex flex-wrap gap-x-4 gap-y-1">
        {collections.map((c) => (
          <li key={c.id}>
            <label className="flex min-h-[32px] items-center gap-2 text-sm">
              <input type="checkbox" checked={selected.includes(c.id)}
                onChange={(e) => onChange(e.target.checked ? [...selected, c.id] : selected.filter((x) => x !== c.id))} />
              {c.name}
            </label>
          </li>
        ))}
      </ul>
      <div className="mt-2 flex gap-2">
        <label htmlFor="new-collection" className="sr-only">New collection name</label>
        <input id="new-collection" className="input" placeholder="New collection" maxLength={100} value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void create(); } }} />
        <button type="button" className="btn-ghost border border-slate-300 dark:border-slate-700" onClick={create}>Add</button>
      </div>
      {error && <p role="alert" className="text-sm text-red-700 dark:text-red-400">{error}</p>}
    </fieldset>
  );
}
