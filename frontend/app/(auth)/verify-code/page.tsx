'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import { errorMessage, RESEND_COOLDOWN_SECONDS, resetFlow } from '@/lib/passwordReset';

export default function VerifyCodePage() {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const [busy, setBusy] = useState(false);
  const [resending, setResending] = useState(false);
  const [cooldown, setCooldown] = useState(0);

  useEffect(() => {
    const stored = resetFlow.email();
    if (!stored) {
      router.replace('/forgot-password');
      return;
    }
    setEmail(stored);
    setInfo('If this email is registered, a verification code has been sent.');
  }, [router]);

  // Tick the resend cooldown based on when the last code was requested.
  useEffect(() => {
    const tick = () => {
      const left = Math.ceil((resetFlow.sentAt() + RESEND_COOLDOWN_SECONDS * 1000 - Date.now()) / 1000);
      setCooldown(Math.max(0, left));
    };
    tick();
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, []);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!/^\d{6}$/.test(code)) {
      setError('Enter the 6-digit code from the email.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const res = await api.verifyResetCode(email, code);
      resetFlow.setToken(res.resetToken);
      router.push('/reset-password');
    } catch (err) {
      setError(errorMessage(err));
      if (err instanceof ApiError && err.code === 'invalid_code') setCode('');
      setBusy(false);
    }
  }

  async function resend() {
    setResending(true);
    setError('');
    try {
      await api.forgotPassword(email);
      resetFlow.markSent();
      setCooldown(RESEND_COOLDOWN_SECONDS);
      setCode('');
      setInfo('A new code has been sent if this email is registered. Earlier codes no longer work.');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setResending(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <form onSubmit={submit} noValidate className="card w-full max-w-sm space-y-4 p-6" aria-labelledby="vc-title">
        <h1 id="vc-title" className="text-xl font-bold">Enter verification code</h1>
        {info && <p role="status" className="text-sm text-slate-600 dark:text-slate-400">{info}</p>}
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Sent to <span className="font-medium break-all">{email}</span>. The code expires in 10 minutes.
        </p>
        <div>
          <label htmlFor="code" className="label">6-digit code</label>
          <input id="code" className="input text-center font-mono text-lg tracking-[0.5em]" required autoFocus
            inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" maxLength={6}
            aria-invalid={!!error} aria-describedby={error ? 'vc-error' : undefined}
            value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} />
        </div>
        {error && <p id="vc-error" role="alert" className="text-sm text-red-700 dark:text-red-400">{error}</p>}
        <button type="submit" className="btn-primary w-full" disabled={busy || code.length !== 6} aria-busy={busy}>
          {busy ? 'Verifying…' : 'Verify code'}
        </button>
        <div className="flex flex-col items-center gap-2 text-sm sm:flex-row sm:justify-between">
          <button type="button" className="btn-ghost" onClick={resend}
            disabled={cooldown > 0 || resending || busy} aria-live="polite">
            {resending ? 'Sending…' : cooldown > 0 ? `Resend code in ${cooldown}s` : 'Resend code'}
          </button>
          <Link className="underline" href="/forgot-password">Use a different email</Link>
        </div>
      </form>
    </main>
  );
}
