'use client';

import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '@/lib/api';
import type { Collection, GroupCount, User } from '@/lib/types';
import { GROUP_GLYPH } from '@/lib/labels';
import { AppProvider, useApp } from './AppContext';
import { SaveDialog } from './SaveDialog';

function isEditable(t: EventTarget | null): boolean {
  const el = t as HTMLElement | null;
  return !!el && (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName));
}

function NavLink({ href, children, count }: { href: string; children: React.ReactNode; count?: number }) {
  const path = usePathname();
  const active = path === href;
  return (
    <Link
      href={href}
      aria-current={active ? 'page' : undefined}
      className={`flex min-h-[40px] items-center justify-between rounded-md px-3 text-sm ${active ? 'bg-indigo-100 font-semibold text-indigo-900 dark:bg-indigo-950 dark:text-indigo-100' : 'hover:bg-slate-200 dark:hover:bg-slate-800'}`}
    >
      <span>{children}</span>
      {count !== undefined && <span className="text-xs text-slate-500">{count}</span>}
    </Link>
  );
}

function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const { version } = useApp();
  const [groups, setGroups] = useState<GroupCount[]>([]);
  const [collections, setCollections] = useState<Collection[]>([]);
  useEffect(() => {
    api.groups().then(setGroups).catch(() => setGroups([]));
    api.collections().then(setCollections).catch(() => setCollections([]));
  }, [version]);
  return (
    <nav aria-label="Main" onClick={onNavigate} className="flex flex-col gap-1">
      <NavLink href="/">Dashboard</NavLink>
      <NavLink href="/all">All bookmarks</NavLink>
      <NavLink href="/favorites">Favorites</NavLink>
      <h2 className="mt-4 px-3 text-xs font-semibold uppercase tracking-wide text-slate-500">Groups</h2>
      {groups.map((g) => (
        <NavLink key={g.id} href={`/groups/${g.id}`} count={g.count}>
          <span aria-hidden="true" className="mr-2">{GROUP_GLYPH[g.id]}</span>{g.displayName}
        </NavLink>
      ))}
      <h2 className="mt-4 px-3 text-xs font-semibold uppercase tracking-wide text-slate-500">Collections</h2>
      {collections.length === 0 && <p className="px-3 text-xs text-slate-500">None yet. Add one from a bookmark.</p>}
      {collections.map((c) => (
        <NavLink key={c.id} href={`/collections/${c.id}`} count={c.count}>
          <span aria-hidden="true" className="mr-2">▤</span>{c.name}
        </NavLink>
      ))}
      <h2 className="mt-4 px-3 text-xs font-semibold uppercase tracking-wide text-slate-500">Library</h2>
      <NavLink href="/tags">Tags</NavLink>
      <NavLink href="/archive">Archive</NavLink>
      <NavLink href="/trash">Trash</NavLink>
      <NavLink href="/settings">Settings</NavLink>
    </nav>
  );
}

function ThemeToggle() {
  const [dark, setDark] = useState(false);
  useEffect(() => setDark(document.documentElement.classList.contains('dark')), []);
  function toggle() {
    const next = !dark;
    document.documentElement.classList.toggle('dark', next);
    localStorage.setItem('theme', next ? 'dark' : 'light');
    setDark(next);
  }
  return (
    <button type="button" className="btn-ghost" onClick={toggle} aria-pressed={dark}>
      {dark ? 'Light theme' : 'Dark theme'}
    </button>
  );
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  const [authError, setAuthError] = useState('');
  const [saveOpen, setSaveOpen] = useState(false);
  const [drawer, setDrawer] = useState(false);
  const [message, setMessage] = useState('');

  useEffect(() => {
    api.me().then(setUser).catch((e) => {
      if (e instanceof ApiError && e.status === 401) router.replace('/login');
      else setAuthError(e instanceof ApiError ? e.message : 'Could not reach the server');
    });
  }, [router]);

  const announce = useCallback((m: string) => {
    setMessage('');
    setTimeout(() => setMessage(m), 20);
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.ctrlKey || e.metaKey || e.altKey || isEditable(e.target)) return;
      if (e.key === 'n' || e.key === 'N') { e.preventDefault(); setSaveOpen(true); }
      if (e.key === '/') {
        // Focus the page's search box if it has one, otherwise go to the full library search.
        const box = document.getElementById('lib-search') as HTMLInputElement | null;
        e.preventDefault();
        if (box) box.focus(); else router.push('/all?focus=search');
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [router]);

  async function logout() {
    try { await api.logout(); } finally { router.replace('/login'); }
  }

  if (authError) return <main className="p-8"><p role="alert">{authError}</p></main>;
  if (!user) return <main className="p-8"><p role="status">Loading…</p></main>;

  return (
    <AppProvider user={user} openSave={() => setSaveOpen(true)} announce={announce}>
      <Inner
        user={user} drawer={drawer} setDrawer={setDrawer} saveOpen={saveOpen} setSaveOpen={setSaveOpen}
        message={message} logout={logout} announce={announce}
      >
        {children}
      </Inner>
    </AppProvider>
  );
}

function Inner({ user, drawer, setDrawer, saveOpen, setSaveOpen, message, logout, announce, children }: {
  user: User; drawer: boolean; setDrawer: (v: boolean) => void; saveOpen: boolean; setSaveOpen: (v: boolean) => void;
  message: string; logout: () => void; announce: (m: string) => void; children: React.ReactNode;
}) {
  const { bump } = useApp();
  return (
    <div className="min-h-screen">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded focus:bg-white focus:p-2">
        Skip to content
      </a>
      <header className="sticky top-0 z-20 flex items-center gap-2 border-b border-slate-200 bg-white/90 px-4 py-2 backdrop-blur dark:border-slate-800 dark:bg-slate-950/90">
        <button
          type="button" className="btn-ghost md:hidden" aria-expanded={drawer} aria-controls="mobile-nav"
          onClick={() => setDrawer(!drawer)}
        >
          Menu
        </button>
        <Link href="/" className="text-lg font-bold">SaveNest</Link>
        <div className="ml-auto flex items-center gap-2">
          <button type="button" className="btn-primary" onClick={() => setSaveOpen(true)} aria-keyshortcuts="n">
            + Save link
          </button>
          <span className="hidden text-sm text-slate-500 sm:inline">{user.displayName}</span>
          <ThemeToggle />
          <button type="button" className="btn-ghost" onClick={logout}>Sign out</button>
        </div>
      </header>

      {drawer && (
        <div id="mobile-nav" className="border-b border-slate-200 p-3 md:hidden dark:border-slate-800">
          <Sidebar onNavigate={() => setDrawer(false)} />
        </div>
      )}

      <div className="flex">
        <aside className="sticky top-[57px] hidden h-[calc(100vh-57px)] w-60 shrink-0 overflow-y-auto border-r border-slate-200 p-3 md:block dark:border-slate-800">
          <Sidebar />
        </aside>
        <main id="main" className="min-w-0 flex-1 p-4 md:p-6">{children}</main>
      </div>

      <SaveDialog
        open={saveOpen}
        onClose={() => setSaveOpen(false)}
        onSaved={() => { bump(); announce('Bookmark saved'); }}
      />
      <div role="status" aria-live="polite" className="sr-only">{message}</div>
    </div>
  );
}
