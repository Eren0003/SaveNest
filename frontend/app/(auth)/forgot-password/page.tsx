'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { api } from '@/lib/api';
import { EMAIL_RE, errorMessage, resetFlow } from '@/lib/passwordReset';

export default function ForgotPasswordPage() {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const value = email.trim();
    if (!EMAIL_RE.test(value)) {
      setError('Enter a valid email address.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await api.forgotPassword(value);
      resetFlow.setEmail(value);
      router.push('/verify-code');
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <form onSubmit={submit} noValidate className="card w-full max-w-sm space-y-4 p-6" aria-labelledby="fp-title">
        <h1 id="fp-title" className="text-xl font-bold">Forgot your password?</h1>
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Enter the email address you registered with and we&apos;ll send you a 6-digit verification code.
        </p>
        <div>
          <label htmlFor="email" className="label">Email</label>
          <input id="email" className="input" type="email" required maxLength={254} autoComplete="email" autoFocus
            aria-invalid={!!error} aria-describedby={error ? 'fp-error' : undefined}
            value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        {error && <p id="fp-error" role="alert" className="text-sm text-red-700 dark:text-red-400">{error}</p>}
        <button type="submit" className="btn-primary w-full" disabled={busy} aria-busy={busy}>
          {busy ? 'Sending code…' : 'Send code'}
        </button>
        <p className="text-center text-sm">
          Remembered it? <Link className="underline" href="/login">Back to sign in</Link>
        </p>
      </form>
    </main>
  );
}
