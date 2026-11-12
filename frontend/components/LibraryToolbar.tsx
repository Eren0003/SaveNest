'use client';

import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import type { Collection, Tag } from '@/lib/types';
import { CONTENT_TYPES, SORTS, withParams } from '@/lib/filters';
import { PROGRESS_LABELS } from '@/lib/labels';
import { useApp } from './AppContext';

export type ViewMode = 'grid' | 'list';

export function useViewMode(): [ViewMode, (m: ViewMode) => void] {
  const [mode, setMode] = useState<ViewMode>('grid');
  useEffect(() => {
    const v = localStorage.getItem('viewMode');
    if (v === 'list' || v === 'grid') setMode(v);
  }, []);
  return [mode, (m) => { localStorage.setItem('viewMode', m); setMode(m); }];
}

/** Search, sort, filters and grid/list toggle. All state except view mode is kept in the URL. */
export function LibraryToolbar({ mode, onMode, showCollection = true }: {
  mode: ViewMode; onMode: (m: ViewMode) => void; showCollection?: boolean;
}) {
  const router = useRouter();
  const path = usePathname();
  const sp = useSearchParams();
  const { version } = useApp();
  const [text, setText] = useState(sp.get('q') ?? '');
  const [tags, setTags] = useState<Tag[]>([]);
  const [collections, setCollections] = useState<Collection[]>([]);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => { setText(sp.get('q') ?? ''); }, [sp]);
  useEffect(() => {
    if (sp.get('focus') === 'search') {
      document.getElementById('lib-search')?.focus();
      router.replace(path + withParams(sp, { focus: undefined }), { scroll: false });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    api.tags().then(setTags).catch(() => {});
    api.collections().then(setCollections).catch(() => {});
  }, [version]);

  const set = (changes: Record<string, string | string[] | undefined>) =>
    router.replace(path + withParams(sp, changes), { scroll: false });

  // Debounced search (the list aborts stale requests when params change).
  function onText(v: string) {
    setText(v);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => set({ q: v.trim() || undefined }), 300);
  }
  useEffect(() => () => clearTimeout(timer.current), []);

  const selectedTags = sp.getAll('tag');
  const activeFilters = ['type', 'progress', 'from', 'to', 'collection'].filter((k) => sp.get(k)).length + selectedTags.length;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="min-w-[12rem] flex-1">
          <label htmlFor="lib-search" className="sr-only">Search bookmarks</label>
          <input
            id="lib-search" type="search" className="input" placeholder="Search title, notes, domain, tags…  ( / )"
            value={text} maxLength={200} onChange={(e) => onText(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor="lib-sort" className="sr-only">Sort by</label>
          <select id="lib-sort" className="input" value={sp.get('sort') ?? 'newest'}
            onChange={(e) => set({ sort: e.target.value === 'newest' ? undefined : e.target.value })}>
            {SORTS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
          </select>
        </div>
        <div role="group" aria-label="Layout" className="flex overflow-hidden rounded-md border border-slate-300 dark:border-slate-700">
          {(['grid', 'list'] as const).map((m) => (
            <button key={m} type="button" aria-pressed={mode === m} onClick={() => onMode(m)}
              className={`min-h-[40px] px-3 text-sm ${mode === m ? 'bg-accent text-accent-fg' : 'hover:bg-slate-200 dark:hover:bg-slate-800'}`}>
              {m === 'grid' ? 'Grid' : 'List'}
            </button>
          ))}
        </div>
      </div>

      <details className="rounded-md border border-slate-200 p-3 dark:border-slate-800" open={activeFilters > 0}>
        <summary className="cursor-pointer text-sm font-medium">
          Filters{activeFilters > 0 ? ` (${activeFilters} active)` : ''}
        </summary>
        <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <div>
            <label htmlFor="f-type" className="label">Content type</label>
            <select id="f-type" className="input" value={sp.get('type') ?? ''} onChange={(e) => set({ type: e.target.value || undefined })}>
              <option value="">Any</option>
              {CONTENT_TYPES.map((t) => <option key={t} value={t}>{t.replace('_', ' ')}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="f-progress" className="label">Progress</label>
            <select id="f-progress" className="input" value={sp.get('progress') ?? ''} onChange={(e) => set({ progress: e.target.value || undefined })}>
              <option value="">Any</option>
              {Object.entries(PROGRESS_LABELS).filter(([k]) => k !== 'none').map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </div>
          {showCollection && (
            <div>
              <label htmlFor="f-collection" className="label">Collection</label>
              <select id="f-collection" className="input" value={sp.get('collection') ?? ''} onChange={(e) => set({ collection: e.target.value || undefined })}>
                <option value="">Any</option>
                {collections.map((c) => <option key={c.id} value={c.id}>{c.name} ({c.count})</option>)}
              </select>
            </div>
          )}
          <div>
            <label htmlFor="f-from" className="label">Saved from</label>
            <input id="f-from" type="date" className="input" value={sp.get('from') ?? ''} onChange={(e) => set({ from: e.target.value || undefined })} />
          </div>
          <div>
            <label htmlFor="f-to" className="label">Saved to</label>
            <input id="f-to" type="date" className="input" value={sp.get('to') ?? ''} onChange={(e) => set({ to: e.target.value || undefined })} />
          </div>
        </div>
        {tags.length > 0 && (
          <fieldset className="mt-3">
            <legend className="label">Tags (all must match)</legend>
            <div className="flex flex-wrap gap-2">
              {tags.map((t) => {
                const on = selectedTags.includes(t.id);
                return (
                  <button key={t.id} type="button" aria-pressed={on}
                    onClick={() => set({ tag: on ? selectedTags.filter((x) => x !== t.id) : [...selectedTags, t.id] })}
                    className={`inline-flex min-h-[32px] items-center rounded-full border px-3 text-sm ${on ? 'border-accent bg-accent text-accent-fg' : 'border-slate-300 hover:bg-slate-200 dark:border-slate-700 dark:hover:bg-slate-800'}`}>
                    #{t.name} <span className="ml-1 opacity-75">{t.count}</span>
                  </button>
                );
              })}
            </div>
          </fieldset>
        )}
        {activeFilters > 0 && (
          <button type="button" className="btn-ghost mt-3"
            onClick={() => set({ type: undefined, progress: undefined, from: undefined, to: undefined, collection: undefined, tag: [] })}>
            Clear filters
          </button>
        )}
      </details>
    </div>
  );
}
