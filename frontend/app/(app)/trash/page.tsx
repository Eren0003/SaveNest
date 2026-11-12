'use client';

import { useState } from 'react';
import { BookmarkList } from '@/components/BookmarkList';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { useApp } from '@/components/AppContext';
import { api, ApiError } from '@/lib/api';

export default function TrashPage() {
  const { bump, announce } = useApp();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);

  async function emptyTrash() {
    setBusy(true);
    try {
      const { deleted } = await api.emptyTrash();
      announce(`Permanently deleted ${deleted} bookmark${deleted === 1 ? '' : 's'}`);
      bump();
    } catch (e) {
      announce(e instanceof ApiError ? e.message : 'Could not empty trash');
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-bold">Trash</h1>
        <button type="button" className="btn-ghost border border-red-300 text-red-700 dark:border-red-800 dark:text-red-400"
          onClick={() => setConfirming(true)} disabled={busy}>Empty trash</button>
      </div>
      <p className="text-sm text-slate-500 dark:text-slate-400">
        Trashed bookmarks are hidden everywhere else. Restore brings one back with its tags and progress; restoring fails if the same link was saved again.
      </p>
      <BookmarkList params={{ view: 'trash' }} empty={<p className="py-12 text-center text-slate-500">Trash is empty.</p>} />
      <ConfirmDialog open={confirming} busy={busy} title="Empty trash?" confirmLabel="Delete all forever"
        body="Every bookmark in the trash will be permanently deleted. This cannot be undone."
        onConfirm={emptyTrash} onCancel={() => setConfirming(false)} />
    </div>
  );
}
