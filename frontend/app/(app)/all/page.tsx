'use client';

import { LibraryView } from '@/components/LibraryView';
import { useApp } from '@/components/AppContext';

export default function AllPage() {
  const { openSave } = useApp();
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">All bookmarks</h1>
      <LibraryView
        base={{}}
        empty={
          <div className="py-12 text-center">
            <p className="text-slate-500">Your library is empty.</p>
            <button type="button" className="btn-primary mt-4" onClick={openSave}>Save a link</button>
          </div>
        }
      />
    </div>
  );
}
