'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { api, ApiError } from '@/lib/api';

export function AuthForm({ mode, notice }: { mode: 'login' | 'register'; notice?: string }) {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const isRegister = mode === 'register';

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      if (isRegister) await api.register(email, password, displayName);
      else await api.login(email, password);
      router.replace('/');
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.fields?.length ? err.fields.map((f) => `${f.field}: ${f.message}`).join('. ') : err.message);
      } else setError('Something went wrong');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <form onSubmit={submit} className="card w-full max-w-sm space-y-4 p-6" aria-labelledby="auth-title">
        <h1 id="auth-title" className="text-xl font-bold">{isRegister ? 'Create your SaveNest account' : 'Sign in to SaveNest'}</h1>
        {notice && !error && (
          <p role="status" className="rounded-md bg-green-50 p-3 text-sm text-green-800 dark:bg-green-950 dark:text-green-300">
            {notice}
          </p>
        )}
        {isRegister && (
          <div>
            <label htmlFor="name" className="label">Display name</label>
            <input id="name" className="input" required maxLength={80} autoComplete="name"
              value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          </div>
        )}
        <div>
          <label htmlFor="email" className="label">Email</label>
          <input id="email" className="input" type="email" required maxLength={254} autoComplete="email"
            value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div>
          <label htmlFor="password" className="label">Password</label>
          <input id="password" className="input" type="password" required maxLength={128}
            minLength={isRegister ? 12 : undefined} aria-describedby={isRegister ? 'pw-hint' : undefined}
            autoComplete={isRegister ? 'new-password' : 'current-password'}
            value={password} onChange={(e) => setPassword(e.target.value)} />
          {isRegister && <p id="pw-hint" className="mt-1 text-xs text-slate-500">At least 12 characters.</p>}
          {!isRegister && (
            <p className="mt-1 text-right text-sm">
              <Link className="underline" href="/forgot-password">Forgot password?</Link>
            </p>
          )}
        </div>
        {error && <p role="alert" className="text-sm text-red-700 dark:text-red-400">{error}</p>}
        <button type="submit" className="btn-primary w-full" disabled={busy}>
          {busy ? 'Please wait…' : isRegister ? 'Create account' : 'Sign in'}
        </button>
        <p className="text-center text-sm">
          {isRegister ? (
            <>Already have an account? <Link className="underline" href="/login">Sign in</Link></>
          ) : (
            <>New here? <Link className="underline" href="/register">Create an account</Link></>
          )}
        </p>
      </form>
    </main>
  );
}
