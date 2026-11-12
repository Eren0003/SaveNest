'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { GroupCount } from '@/lib/types';
import { GROUP_GLYPH } from '@/lib/labels';
import { BookmarkList } from '@/components/BookmarkList';
import { useApp } from '@/components/AppContext';

export default function DashboardPage() {
  const { version, openSave, user } = useApp();
  const [groups, setGroups] = useState<GroupCount[] | null>(null);
  const [hasFavorites, setHasFavorites] = useState(false);

  useEffect(() => {
    api.groups().then(setGroups).catch(() => setGroups([]));
    api.listBookmarks({ favorite: true, limit: 1 }).then((p) => setHasFavorites(p.items.length > 0)).catch(() => {});
  }, [version]);

  const total = groups?.reduce((n, g) => n + g.count, 0) ?? 0;

  if (groups && total === 0) {
    return (
      <section className="mx-auto max-w-xl py-16 text-center">
        <h1 className="text-2xl font-bold">Welcome, {user.displayName}</h1>
        <p className="mt-3 text-slate-600 dark:text-slate-400">
          Paste any link and SaveNest files it automatically: YouTube videos go to YouTube, Amazon and Flipkart
          products go to Products, and so on. No folders to set up first.
        </p>
        <button type="button" className="btn-primary mt-6" onClick={openSave}>Save your first link</button>
        <p className="mt-3 text-xs text-slate-500">Tip: press N anywhere to open the save dialog.</p>
      </section>
    );
  }

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-bold">Dashboard</h1>

      <section aria-labelledby="groups-h">
        <h2 id="groups-h" className="mb-3 text-lg font-semibold">Groups</h2>
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
          {(groups ?? []).map((g) => (
            <li key={g.id}>
              <Link href={`/groups/${g.id}`} className="card flex flex-col gap-1 p-3 hover:border-accent">
                <span aria-hidden="true" className="text-xl">{GROUP_GLYPH[g.id]}</span>
                <span className="font-medium">{g.displayName}</span>
                <span className="text-sm text-slate-500">{g.count} {g.count === 1 ? 'bookmark' : 'bookmarks'}</span>
              </Link>
            </li>
          ))}
        </ul>
      </section>

      {hasFavorites && (
        <section aria-labelledby="fav-h">
          <div className="mb-3 flex items-baseline justify-between">
            <h2 id="fav-h" className="text-lg font-semibold">Favorites</h2>
            <Link href="/favorites" className="text-sm underline">See all</Link>
          </div>
          <BookmarkList params={{ favorite: true }} max={4} empty={null} />
        </section>
      )}

      <section aria-labelledby="recent-h">
        <div className="mb-3 flex items-baseline justify-between">
          <h2 id="recent-h" className="text-lg font-semibold">Recently saved</h2>
          <Link href="/all" className="text-sm underline">See all</Link>
        </div>
        <BookmarkList params={{}} max={8} empty={<p className="text-sm text-slate-500">Nothing saved yet.</p>} />
      </section>
    </div>
  );
}
