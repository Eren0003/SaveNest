import { ApiError } from './api';

/** Flow state lives in sessionStorage (per tab, cleared on close); never the code itself. */
const EMAIL_KEY = 'savenest.reset.email';
const TOKEN_KEY = 'savenest.reset.token';
const SENT_AT_KEY = 'savenest.reset.sentAt';

export const RESEND_COOLDOWN_SECONDS = 60;
export const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function store() {
  return typeof window === 'undefined' ? null : window.sessionStorage;
}

export const resetFlow = {
  email: () => store()?.getItem(EMAIL_KEY) ?? '',
  setEmail: (email: string) => {
    store()?.setItem(EMAIL_KEY, email);
    store()?.setItem(SENT_AT_KEY, String(Date.now()));
  },
  sentAt: () => Number(store()?.getItem(SENT_AT_KEY) ?? 0),
  markSent: () => store()?.setItem(SENT_AT_KEY, String(Date.now())),
  token: () => store()?.getItem(TOKEN_KEY) ?? '',
  setToken: (t: string) => store()?.setItem(TOKEN_KEY, t),
  clear: () => [EMAIL_KEY, TOKEN_KEY, SENT_AT_KEY].forEach((k) => store()?.removeItem(k)),
};

export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    return err.fields?.length ? err.fields.map((f) => f.message).join('. ') : err.message;
  }
  return 'Something went wrong. Please try again.';
}

/** Mirrors the server rules (the server remains the authority). */
export function passwordProblem(pw: string, email: string): string | null {
  if (pw.length < 12) return 'Password must be at least 12 characters.';
  if (pw.length > 128) return 'Password must be at most 128 characters.';
  if (new Set(pw).size < 4) return 'Password is too simple.';
  if (email && pw.toLowerCase().includes(email.toLowerCase())) return 'Password must not contain your email address.';
  return null;
}
