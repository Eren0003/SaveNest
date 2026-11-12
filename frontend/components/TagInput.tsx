'use client';

import { useState } from 'react';

/** Simple accessible tag editor: type a name and press Enter or comma. Suggestions come from existing tags. */
export function TagInput({ id, value, onChange, suggestions }: {
  id: string; value: string[]; onChange: (v: string[]) => void; suggestions: string[];
}) {
  const [text, setText] = useState('');
  const has = (n: string) => value.some((v) => v.toLowerCase() === n.toLowerCase());

  function add(raw: string) {
    const n = raw.trim().replace(/\s+/g, ' ');
    if (n && n.length <= 50 && !has(n) && value.length < 20) onChange([...value, n]);
    setText('');
  }

  return (
    <div>
      <div className="flex flex-wrap gap-1">
        {value.map((t) => (
          <span key={t} className="badge">
            #{t}
            <button type="button" className="ml-1" aria-label={`Remove tag ${t}`} onClick={() => onChange(value.filter((x) => x !== t))}>✕</button>
          </span>
        ))}
      </div>
      <input
        id={id} className="input mt-1" value={text} maxLength={50} list={`${id}-list`} placeholder="Add a tag and press Enter"
        onChange={(e) => { if (e.target.value.endsWith(',')) add(e.target.value.slice(0, -1)); else setText(e.target.value); }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') { e.preventDefault(); add(text); }
          if (e.key === 'Backspace' && !text && value.length) onChange(value.slice(0, -1));
        }}
        onBlur={() => add(text)}
      />
      <datalist id={`${id}-list`}>
        {suggestions.filter((s) => !has(s)).map((s) => <option key={s} value={s} />)}
      </datalist>
    </div>
  );
}
