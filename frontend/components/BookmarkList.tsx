'use client';

import { useEffect, useState } from 'react';
import { api, ApiError, type ListParams } from '@/lib/api';
import type { Bookmark } from '@/lib/types';
import { BookmarkCard } from './BookmarkCard';
import { useApp } from './AppContext';
import { isEnriching } from '@/lib/metadata';

/** Cursor-paginated bookmark grid with an accessible "Load more" button. */
export function BookmarkList({ params, empty, pageSize = 24, max, mode = 'grid' }: {
  params: ListParams; empty: React.ReactNode; pageSize?: number; max?: number; mode?: 'grid' | 'list';
}) {
  const { version } = useApp();
  const [items, setItems] = useState<Bookmark[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState('');
  const [loadingMore, setLoadingMore] = useState(false);
  const key = JSON.stringify(params);

  useEffect(() => {
    const ctrl = new AbortController();
    setState('loading');
    api.listBookmarks({ ...params, limit: max ?? pageSize }, ctrl.signal)
      .then((p) => { setItems(p.items); setCursor(p.nextCursor); setState('ready'); })
      .catch((e) => {
        if ((e as Error).name === 'AbortError') return;
        setError(e instanceof ApiError ? e.message : 'Could not load bookmarks');
        setState('error');
      });
    return () => ctrl.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, version]);

  async function loadMore() {
    if (!cursor) return;
    setLoadingMore(true);
    try {
      const p = await api.listBookmarks({ ...params, limit: pageSize, cursor });
      setItems((prev) => [...prev, ...p.items]);
      setCursor(p.nextCursor);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not load more');
    } finally {
      setLoadingMore(false);
    }
  }

  // Poll pending previews so cards update when the worker finishes (bounded: stops after ~2 minutes).
  const pendingIds = items.filter(isEnriching).map((b) => b.id).join(',');
  useEffect(() => {
    if (!pendingIds) return;
    let tries = 0;
    const timer = setInterval(async () => {
      if (++tries > 40) { clearInterval(timer); return; }
      const ids = pendingIds.split(',');
      const fresh = await Promise.all(ids.map((id) => api.getBookmark(id).catch(() => null)));
      setItems((prev) => prev.map((x) => fresh.find((f) => f?.id === x.id) ?? x));
    }, 3000);
    return () => clearInterval(timer);
  }, [pendingIds]);

  function onChange(id: string, b: Bookmark | null) {
    setItems((prev) => prev.flatMap((x) => (x.id !== id ? [x] : b ? [b] : [])));
  }

  if (state === 'loading') return <p role="status" className="text-sm text-slate-500">Loading bookmarks…</p>;
  if (state === 'error') return <p role="alert" className="text-sm text-red-700 dark:text-red-400">{error}</p>;
  if (items.length === 0) return <>{empty}</>;

  return (
    <div>
      <ul className={mode === 'list' ? 'flex flex-col gap-3' : 'grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4'}>
        {items.map((b) => (
          <li key={b.id} className="flex"><div className="w-full"><BookmarkCard b={b} compact={mode === 'list'} onChange={(n) => onChange(b.id, n)} /></div></li>
        ))}
      </ul>
      {cursor && max === undefined && (
        <div className="mt-6 flex justify-center">
          <button type="button" className="btn-ghost border border-slate-300 dark:border-slate-700" onClick={loadMore} disabled={loadingMore}>
            {loadingMore ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}
    </div>
  );
}
