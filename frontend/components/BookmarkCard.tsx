'use client';

import Link from 'next/link';
import { useState } from 'react';
import { api, ApiError } from '@/lib/api';
import type { Bookmark } from '@/lib/types';
import { GROUP_GLYPH, PROGRESS_LABELS, hostHue } from '@/lib/labels';
import { GroupBadge, PlatformBadge } from './Badges';
import { useApp } from './AppContext';
import { ConfirmDialog } from './ConfirmDialog';
import { isEnriching, metadataExplanation, metadataLabel } from '@/lib/metadata';

export function BookmarkCard({ b, onChange, compact = false }: {
  b: Bookmark; onChange: (b: Bookmark | null) => void; compact?: boolean;
}) {
  const { bump, announce } = useApp();
  const [busy, setBusy] = useState(false);
  const inTrash = b.deletedAt !== null;

  async function run(action: () => Promise<Bookmark | null>, msg: string) {
    setBusy(true);
    try {
      const updated = await action();
      onChange(updated);
      bump();
      announce(msg);
    } catch (e) {
      announce(e instanceof ApiError ? e.message : 'Action failed');
    } finally {
      setBusy(false);
    }
  }

  const toggleFavorite = () =>
    run(() => api.updateBookmark(b.id, { favorite: !b.favorite }), b.favorite ? 'Removed from favorites' : 'Added to favorites');
  const toggleArchive = () =>
    run(() => api.updateBookmark(b.id, { lifecycleState: b.lifecycleState === 'archived' ? 'active' : 'archived' }),
      b.lifecycleState === 'archived' ? 'Unarchived' : 'Archived');
  const trash = () => run(async () => { await api.trashBookmark(b.id); return null; }, 'Moved to trash');
  const restore = () => run(async () => {
    try {
      await api.restoreBookmark(b.id);
      return null; // leaves the trash list
    } catch (e) {
      if (e instanceof ApiError && e.code === 'duplicate_bookmark') {
        throw new ApiError(409, e.code, 'Cannot restore: this link is already saved. Delete one of the copies first.');
      }
      throw e;
    }
  }, 'Restored');
  const [confirming, setConfirming] = useState(false);
  const deleteForever = async () => {
    await run(async () => { await api.deleteBookmarkPermanently(b.id); return null; }, 'Permanently deleted');
    setConfirming(false);
  };

  const [imgFailed, setImgFailed] = useState(false);
  const src = api.previewSrc(b);
  const hue = hostHue(b.host);
  const saved = new Date(b.createdAt).toLocaleDateString();

  return (
    <article className={`card flex overflow-hidden ${compact ? 'flex-row' : 'flex-col'}`} aria-labelledby={`t-${b.id}`}>
      {src && !imgFailed ? (
        // Decorative: the title is the accessible name. Fixed aspect ratio avoids layout shift.
        // eslint-disable-next-line @next/next/no-img-element
        <img src={src} alt="" loading="lazy" decoding="async" onError={() => setImgFailed(true)}
          className={`aspect-[16/7] bg-slate-100 object-cover dark:bg-slate-800 ${compact ? 'hidden w-40 shrink-0 sm:block' : 'w-full'}`} />
      ) : (
        <div
          aria-hidden="true"
          className={`flex aspect-[16/7] items-center justify-center text-3xl text-white ${compact ? 'hidden w-40 shrink-0 sm:flex' : ''}`}
          style={{ background: `linear-gradient(135deg, hsl(${hue} 55% 45%), hsl(${(hue + 40) % 360} 55% 35%))` }}
        >
          {GROUP_GLYPH[b.groupId]}
        </div>
      )}
      <div className="flex flex-1 flex-col gap-2 p-3">
        <div className="flex items-start justify-between gap-2">
          <h3 id={`t-${b.id}`} className="line-clamp-2 font-semibold break-words">
            <Link href={`/bookmarks/${b.id}`} className="hover:underline">{b.title}</Link>
          </h3>
          {!inTrash && (
            <button
              type="button"
              className="btn-ghost px-2"
              onClick={toggleFavorite}
              disabled={busy}
              aria-pressed={b.favorite}
              aria-label={b.favorite ? `Unfavorite ${b.title}` : `Favorite ${b.title}`}
            >
              <span aria-hidden="true">{b.favorite ? '★' : '☆'}</span>
            </button>
          )}
        </div>
        <p className="truncate text-xs text-slate-500 dark:text-slate-400">{b.host}</p>
        <div className="flex flex-wrap gap-1">
          <GroupBadge id={b.groupId} />
          <PlatformBadge platform={b.platform} />
          {b.progressState !== 'none' && <span className="badge">{PROGRESS_LABELS[b.progressState]}</span>}
          {b.metadataStatus !== 'ready' && (
            <span className={`badge ${isEnriching(b) ? 'text-amber-700 dark:text-amber-400' : b.metadataStatus === 'partial' ? '' : 'text-slate-600 dark:text-slate-400'}`}
              title={metadataExplanation(b) ?? undefined}>
              {metadataLabel(b)}
            </span>
          )}
          {b.lifecycleState === 'archived' && <span className="badge">Archived</span>}
        </div>
        {(b.tags.length > 0 || b.collections.length > 0) && (
          <ul className="flex flex-wrap gap-1 text-xs" aria-label="Tags and collections">
            {b.collections.map((c) => (
              <li key={c.id}><Link href={`/collections/${c.id}`} className="badge hover:underline">▤ {c.name}</Link></li>
            ))}
            {b.tags.map((t) => (
              <li key={t.id}><Link href={`/all?tag=${t.id}`} className="badge hover:underline">#{t.name}</Link></li>
            ))}
          </ul>
        )}
        <p className="mt-auto text-xs text-slate-500 dark:text-slate-400">Saved {saved}</p>
        <div className="flex flex-wrap gap-1 border-t border-slate-200 pt-2 dark:border-slate-800">
          <a className="btn-ghost" href={b.url} target="_blank" rel="noopener noreferrer nofollow"
            onClick={() => { api.openBookmark(b.id).catch(() => {}); }}>
            Open<span className="sr-only"> {b.title} (opens in new tab)</span>
          </a>
          <Link className="btn-ghost" href={`/bookmarks/${b.id}`}>
            Edit<span className="sr-only"> {b.title}</span>
          </Link>
          {!inTrash && (
            <>
              <button type="button" className="btn-ghost" onClick={toggleArchive} disabled={busy}>
                {b.lifecycleState === 'archived' ? 'Unarchive' : 'Archive'}
              </button>
              <button type="button" className="btn-ghost text-red-700 dark:text-red-400" onClick={trash} disabled={busy}>
                Trash
              </button>
            </>
          )}
          {inTrash && (
            <>
              <button type="button" className="btn-ghost" onClick={restore} disabled={busy}>
                Restore<span className="sr-only"> {b.title}</span>
              </button>
              <button type="button" className="btn-ghost text-red-700 dark:text-red-400" onClick={() => setConfirming(true)} disabled={busy}>
                Delete forever<span className="sr-only"> {b.title}</span>
              </button>
            </>
          )}
        </div>
      </div>
      {inTrash && (
        <ConfirmDialog open={confirming} busy={busy} title="Delete permanently?" confirmLabel="Delete forever"
          body={<>“{b.title}” and its notes, tags and progress will be removed. This cannot be undone.</>}
          onConfirm={deleteForever} onCancel={() => setConfirming(false)} />
      )}
    </article>
  );
}
