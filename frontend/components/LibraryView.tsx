'use client';

import { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import type { ListParams } from '@/lib/api';
import { paramsFromUrl } from '@/lib/filters';
import { BookmarkList } from './BookmarkList';
import { LibraryToolbar, useViewMode } from './LibraryToolbar';

function Inner({ base, empty, showCollection }: { base: ListParams; empty: React.ReactNode; showCollection?: boolean }) {
  const sp = useSearchParams();
  const [mode, setMode] = useViewMode();
  const fromUrl = paramsFromUrl(sp);
  const params: ListParams = { ...fromUrl, ...base, platform: base.platform ?? fromUrl.platform };
  const filtered = !!(fromUrl.q || fromUrl.contentType || fromUrl.progress || fromUrl.tag || fromUrl.collection || fromUrl.from || fromUrl.to);
  return (
    <div className="space-y-4">
      <LibraryToolbar mode={mode} onMode={setMode} showCollection={showCollection} />
      <BookmarkList params={params} mode={mode}
        empty={filtered
          ? <p className="py-12 text-center text-slate-500">No bookmarks match your search or filters.</p>
          : empty} />
    </div>
  );
}

/** Toolbar + list wired to URL filter state. `base` params are fixed by the page (group, favorites…). */
export function LibraryView(props: { base: ListParams; empty: React.ReactNode; showCollection?: boolean }) {
  return <Suspense fallback={<p role="status">Loading…</p>}><Inner {...props} /></Suspense>;
}
