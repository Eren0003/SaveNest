'use client';

import { useEffect, useRef } from 'react';

/**
 * Modal confirmation built on the native <dialog>: showModal() makes the rest of the page inert,
 * traps focus, closes on Escape, and the browser restores focus to the opener on close.
 * The safe (cancel) action receives initial focus.
 */
export function ConfirmDialog({ open, title, body, confirmLabel, onConfirm, onCancel, busy = false }: {
  open: boolean; title: string; body: React.ReactNode; confirmLabel: string;
  onConfirm: () => void; onCancel: () => void; busy?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) {
      d.showModal();
      cancelRef.current?.focus();
    } else if (!open && d.open) {
      d.close();
    }
  }, [open]);

  return (
    <dialog ref={ref} aria-labelledby="confirm-title" aria-describedby="confirm-body"
      onCancel={(e) => { e.preventDefault(); if (!busy) onCancel(); }}
      className="card w-[min(28rem,92vw)] p-5 backdrop:bg-black/50">
      <h2 id="confirm-title" className="text-lg font-semibold">{title}</h2>
      <div id="confirm-body" className="mt-2 text-sm text-slate-600 dark:text-slate-300">{body}</div>
      <div className="mt-5 flex justify-end gap-2">
        <button ref={cancelRef} type="button" className="btn-ghost border border-slate-300 dark:border-slate-700"
          onClick={onCancel} disabled={busy}>Cancel</button>
        <button type="button" className="btn-ghost bg-red-700 text-white hover:bg-red-800"
          onClick={onConfirm} disabled={busy}>{busy ? 'Working…' : confirmLabel}</button>
      </div>
    </dialog>
  );
}
