import type { ListParams } from './api';

export const SORTS = [
  { value: 'newest', label: 'Newest' },
  { value: 'oldest', label: 'Oldest' },
  { value: 'title', label: 'Title (A–Z)' },
  { value: 'opened', label: 'Recently opened' },
] as const;

export const CONTENT_TYPES = [
  'video', 'channel', 'playlist', 'product', 'shopping_page', 'movie', 'series', 'anime', 'media_title',
  'image', 'article', 'post', 'website', 'unknown',
];

/** Filter state lives in the URL so back/forward and shared links work. */
export function paramsFromUrl(sp: URLSearchParams): ListParams {
  return {
    q: sp.get('q') || undefined,
    sort: sp.get('sort') || undefined,
    platform: sp.get('platform') || undefined,
    contentType: sp.get('type') || undefined,
    progress: sp.get('progress') || undefined,
    tag: sp.getAll('tag').length ? sp.getAll('tag') : undefined,
    collection: sp.get('collection') || undefined,
    from: sp.get('from') || undefined,
    to: sp.get('to') || undefined,
  };
}

/** Returns a new query string with the given keys replaced (empty/undefined removes the key). */
export function withParams(sp: URLSearchParams, changes: Record<string, string | string[] | undefined>): string {
  const next = new URLSearchParams(sp.toString());
  for (const [k, v] of Object.entries(changes)) {
    next.delete(k);
    if (Array.isArray(v)) v.forEach((x) => next.append(k, x));
    else if (v) next.set(k, v);
  }
  const s = next.toString();
  return s ? `?${s}` : '';
}
