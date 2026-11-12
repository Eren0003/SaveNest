'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import { errorMessage, passwordProblem, resetFlow } from '@/lib/passwordReset';

export default function ResetPasswordPage() {
  const router = useRouter();
  const [token, setToken] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [expired, setExpired] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const t = resetFlow.token();
    if (!t) {
      router.replace('/forgot-password');
      return;
    }
    setToken(t);
    setEmail(resetFlow.email());
  }, [router]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const problem = passwordProblem(password, email);
    if (problem) return setError(problem);
    if (password !== confirm) return setError('Passwords do not match.');
    setBusy(true);
    setError('');
    try {
      await api.resetPassword(token, password, confirm);
      resetFlow.clear();
      router.replace('/login?reset=success');
    } catch (err) {
      setError(errorMessage(err));
      if (err instanceof ApiError && err.code === 'invalid_reset_token') setExpired(true);
      setBusy(false);
    }
  }

  const mismatch = confirm.length > 0 && password !== confirm;

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <form onSubmit={submit} noValidate className="card w-full max-w-sm space-y-4 p-6" aria-labelledby="rp-title">
        <h1 id="rp-title" className="text-xl font-bold">Choose a new password</h1>
        <div>
          <label htmlFor="new-password" className="label">New password</label>
          <input id="new-password" className="input" type="password" required minLength={12} maxLength={128}
            autoComplete="new-password" autoFocus aria-describedby="rp-hint"
            value={password} onChange={(e) => setPassword(e.target.value)} />
          <p id="rp-hint" className="mt-1 text-xs text-slate-500">At least 12 characters. Don&apos;t include your email.</p>
        </div>
        <div>
          <label htmlFor="confirm-password" className="label">Confirm new password</label>
          <input id="confirm-password" className="input" type="password" required maxLength={128}
            autoComplete="new-password" aria-invalid={mismatch} aria-describedby={mismatch ? 'rp-mismatch' : undefined}
            value={confirm} onChange={(e) => setConfirm(e.target.value)} />
          {mismatch && <p id="rp-mismatch" className="mt-1 text-xs text-red-700 dark:text-red-400">Passwords do not match.</p>}
        </div>
        {error && <p role="alert" className="text-sm text-red-700 dark:text-red-400">{error}</p>}
        {expired ? (
          <Link href="/forgot-password" className="btn-primary w-full text-center" onClick={() => resetFlow.clear()}>
            Request a new code
          </Link>
        ) : (
          <button type="submit" className="btn-primary w-full" disabled={busy} aria-busy={busy}>
            {busy ? 'Saving…' : 'Reset password'}
          </button>
        )}
        <p className="text-center text-sm"><Link className="underline" href="/login">Back to sign in</Link></p>
      </form>
    </main>
  );
}
