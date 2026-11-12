'use client';

import Link from 'next/link';
import { notFound, useParams, useSearchParams } from 'next/navigation';
import { Suspense, useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { GroupCount, GroupId } from '@/lib/types';
import { GROUP_IDS, GROUP_LABELS, platformLabel } from '@/lib/labels';
import { LibraryView } from '@/components/LibraryView';
import { useApp } from '@/components/AppContext';

function GroupView() {
  const { id } = useParams<{ id: string }>();
  const search = useSearchParams();
  const keep = (platformValue?: string) => { const n = new URLSearchParams(search.toString()); n.delete('platform'); if (platformValue) n.set('platform', platformValue); const q = n.toString(); return q ? `?${q}` : ''; };
  const platform = search.get('platform') ?? undefined;
  const { version, openSave } = useApp();
  const [group, setGroup] = useState<GroupCount | null>(null);

  const valid = GROUP_IDS.includes(id as GroupId);
  useEffect(() => {
    if (!valid) return;
    api.groups().then((gs) => setGroup(gs.find((g) => g.id === id) ?? null)).catch(() => {});
  }, [id, valid, version]);
  if (!valid) notFound();

  const gid = id as GroupId;
  const chip = (active: boolean) =>
    `inline-flex min-h-[36px] items-center rounded-full border px-3 text-sm ${active ? 'border-accent bg-accent text-accent-fg' : 'border-slate-300 hover:bg-slate-200 dark:border-slate-700 dark:hover:bg-slate-800'}`;

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold">{GROUP_LABELS[gid]}</h1>
        {group && <p className="text-sm text-slate-500">{group.count} {group.count === 1 ? 'bookmark' : 'bookmarks'}</p>}
      </div>

      {/* Platform chips come from real saved data; filter state lives in the URL. */}
      {group && group.platforms.length > 0 && (
        <nav aria-label="Platform filter">
          <ul className="flex flex-wrap gap-2">
            <li>
              <Link href={`/groups/${gid}${keep()}`} className={chip(!platform)} aria-current={!platform ? 'true' : undefined}>
                All <span className="ml-1 opacity-75">{group.count}</span>
              </Link>
            </li>
            {group.platforms.map((p) => (
              <li key={p.platform}>
                <Link
                  href={`/groups/${gid}${keep(p.platform)}`}
                  className={chip(platform === p.platform)}
                  aria-current={platform === p.platform ? 'true' : undefined}
                >
                  {platformLabel(p.platform)} <span className="ml-1 opacity-75">{p.count}</span>
                </Link>
              </li>
            ))}
          </ul>
        </nav>
      )}

      <LibraryView
        base={{ group: gid }}
        empty={
          <div className="py-12 text-center">
            <p className="text-slate-500">Nothing in {GROUP_LABELS[gid]}{platform ? ` from ${platformLabel(platform)}` : ''} yet.</p>
            <button type="button" className="btn-primary mt-4" onClick={openSave}>Save a link</button>
          </div>
        }
      />
    </div>
  );
}

export default function GroupPage() {
  return (
    <Suspense fallback={<p role="status">Loading…</p>}>
      <GroupView />
    </Suspense>
  );
}
