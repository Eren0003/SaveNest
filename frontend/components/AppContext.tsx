'use client';

import { createContext, useCallback, useContext, useState } from 'react';
import type { User } from '@/lib/types';

interface AppState {
  user: User;
  /** Incremented after any mutation so lists and counts refetch. */
  version: number;
  bump: () => void;
  openSave: () => void;
  announce: (msg: string) => void;
}

const Ctx = createContext<AppState | null>(null);

export function useApp(): AppState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useApp must be used inside AppProvider');
  return v;
}

export function AppProvider({ user, openSave, announce, children }: {
  user: User; openSave: () => void; announce: (m: string) => void; children: React.ReactNode;
}) {
  const [version, setVersion] = useState(0);
  const bump = useCallback(() => setVersion((v) => v + 1), []);
  return <Ctx.Provider value={{ user, version, bump, openSave, announce }}>{children}</Ctx.Provider>;
}
