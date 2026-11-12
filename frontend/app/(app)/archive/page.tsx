'use client';

import { LibraryView } from '@/components/LibraryView';

export default function ArchivePage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Archive</h1>
      <p className="text-sm text-slate-500">Archived bookmarks are hidden from groups and counts but keep their progress.</p>
      <LibraryView base={{ view: 'archived' }} empty={<p className="py-12 text-center text-slate-500">Nothing archived.</p>} />
    </div>
  );
}
