'use client';

import { LibraryView } from '@/components/LibraryView';

export default function FavoritesPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Favorites</h1>
      <LibraryView
        base={{ favorite: true }}
        empty={<p className="py-12 text-center text-slate-500">No favorites yet. Use the ☆ button on a card to add one.</p>}
      />
    </div>
  );
}
