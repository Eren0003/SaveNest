'use client';

import { useRouter } from 'next/navigation';
import { useId, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import { useApp } from '@/components/AppContext';
import { ConfirmDialog } from '@/components/ConfirmDialog';

export default function SettingsPage() {
  const router = useRouter();
  const { user, announce } = useApp();
  const pwId = useId();
  const [password, setPassword] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function deleteAccount() {
    setBusy(true);
    setError('');
    try {
      await api.deleteAccount(password);
      announce('Account deleted');
      router.replace('/login');
    } catch (e) {
      setConfirmOpen(false);
      setError(e instanceof ApiError ? e.message : 'Could not delete the account. Try again.');
    } finally {
      setBusy(false);
      setPassword('');
    }
  }

  return (
    <div className="max-w-2xl space-y-6">
      <h1 className="text-2xl font-bold">Settings</h1>

      <section aria-labelledby="account-h" className="card space-y-1 p-4">
        <h2 id="account-h" className="text-lg font-semibold">Account</h2>
        <p className="text-sm text-slate-600 dark:text-slate-300">{user.displayName} · {user.email}</p>
      </section>

      <section aria-labelledby="export-h" className="card space-y-3 p-4">
        <h2 id="export-h" className="text-lg font-semibold">Export your data</h2>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          Downloads every bookmark you own, including archived and trashed ones. JSON also includes your tags and
          collections; CSV opens in spreadsheet apps.
        </p>
        <div className="flex flex-wrap gap-2">
          <a className="btn-ghost border border-slate-300 dark:border-slate-700" href={api.exportUrl('json')} download>
            Download JSON
          </a>
          <a className="btn-ghost border border-slate-300 dark:border-slate-700" href={api.exportUrl('csv')} download>
            Download CSV
          </a>
        </div>
      </section>

      <section aria-labelledby="danger-h" className="card space-y-3 border-red-300 p-4 dark:border-red-900">
        <h2 id="danger-h" className="text-lg font-semibold text-red-700 dark:text-red-400">Delete your account</h2>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          This permanently deletes your account and every bookmark, tag and collection you own, on every device.
          It cannot be undone. Export your data first if you want a copy.
        </p>
        <form
          className="space-y-3"
          onSubmit={(e) => { e.preventDefault(); setError(''); setConfirmOpen(true); }}
        >
          <div className="space-y-1">
            <label htmlFor={pwId} className="block text-sm font-medium">Confirm your password</label>
            <input
              id={pwId} type="password" required autoComplete="current-password" value={password}
              onChange={(e) => setPassword(e.target.value)}
              aria-describedby={error ? `${pwId}-err` : undefined}
              className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900"
            />
          </div>
          {error && <p id={`${pwId}-err`} role="alert" className="text-sm text-red-700 dark:text-red-400">{error}</p>}
          <button type="submit" className="btn-ghost bg-red-700 text-white hover:bg-red-800" disabled={!password || busy}>
            Delete account
          </button>
        </form>
      </section>

      <ConfirmDialog
        open={confirmOpen}
        title="Delete your account?"
        body="Your account, bookmarks, tags and collections will be permanently deleted. This cannot be undone."
        confirmLabel="Delete forever"
        busy={busy}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={deleteAccount}
      />
    </div>
  );
}
