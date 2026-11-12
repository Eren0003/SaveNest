import type { Bookmark, BookmarkPage, ClassifyResult, Collection, GroupCount, Tag, User, View } from './types';

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public requestId?: string,
    public details?: Record<string, unknown>,
    public fields?: { field: string; message: string }[],
  ) {
    super(message);
  }
}

function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null;
  const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return m ? decodeURIComponent(m[1]) : null;
}

async function ensureCsrf(force = false): Promise<string> {
  let token = force ? null : readCookie('XSRF-TOKEN');
  if (!token) {
    await fetch('/api/auth/csrf', { credentials: 'same-origin' });
    token = readCookie('XSRF-TOKEN');
  }
  return token ?? '';
}

async function request<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const unsafe = method !== 'GET';
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const send = async (forceCsrf: boolean) => {
    if (unsafe) headers['X-XSRF-TOKEN'] = await ensureCsrf(forceCsrf);
    return fetch(path, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      credentials: 'same-origin',
      signal,
    });
  };

  let res: Response;
  try {
    res = await send(false);
    // CSRF token rotates on login/logout; retry once with a fresh token.
    if (res.status === 403 && unsafe) res = await send(true);
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError(0, 'network_error', 'Network request failed. Check your connection and try again.');
  }

  if (res.status === 204) return undefined as T;
  const json = await res.json().catch(() => null);
  if (!res.ok) {
    const err = json?.error;
    throw new ApiError(res.status, err?.code ?? 'http_' + res.status, err?.message ?? 'Request failed',
      err?.requestId, err?.details, err?.fields);
  }
  return json?.data as T;
}

export interface AccountDeletionSummary {
  bookmarks: number;
  tags: number;
  collections: number;
  metadataJobs: number;
  sessions: number;
}

export interface ListParams {
  view?: View;
  group?: string;
  platform?: string;
  favorite?: boolean;
  q?: string;
  sort?: string;
  contentType?: string;
  progress?: string;
  tag?: string[];
  collection?: string;
  from?: string;
  to?: string;
  limit?: number;
  cursor?: string | null;
}

export interface BookmarkPatch {
  title?: string;
  description?: string;
  notes?: string;
  groupId?: string;
  clearGroupOverride?: boolean;
  favorite?: boolean;
  lifecycleState?: 'active' | 'archived';
  progressState?: string;
  tagNames?: string[];
  collectionIds?: string[];
}

export const api = {
  me: () => request<User>('GET', '/api/auth/me'),
  login: (email: string, password: string) => request<User>('POST', '/api/auth/login', { email, password }),
  register: (email: string, password: string, displayName: string) =>
    request<User>('POST', '/api/auth/register', { email, password, displayName }),
  logout: () => request<void>('POST', '/api/auth/logout'),

  classify: (url: string, signal?: AbortSignal) => request<ClassifyResult>('POST', '/api/classify', { url }, signal),
  groups: () => request<GroupCount[]>('GET', '/api/groups'),

  listBookmarks: (p: ListParams, signal?: AbortSignal) => {
    const q = new URLSearchParams();
    if (p.view) q.set('view', p.view);
    if (p.group) q.set('group', p.group);
    if (p.platform) q.set('platform', p.platform);
    if (p.favorite !== undefined) q.set('favorite', String(p.favorite));
    if (p.q) q.set('q', p.q);
    if (p.sort) q.set('sort', p.sort);
    if (p.contentType) q.set('contentType', p.contentType);
    if (p.progress) q.set('progress', p.progress);
    p.tag?.forEach((t) => q.append('tag', t));
    if (p.collection) q.set('collection', p.collection);
    if (p.from) q.set('from', p.from);
    if (p.to) q.set('to', p.to);
    if (p.limit) q.set('limit', String(p.limit));
    if (p.cursor) q.set('cursor', p.cursor);
    return request<BookmarkPage>('GET', '/api/bookmarks?' + q.toString(), undefined, signal);
  },
  getBookmark: (id: string) => request<Bookmark>('GET', `/api/bookmarks/${encodeURIComponent(id)}`),
  createBookmark: (b: { url: string; notes?: string; groupId?: string; progressState?: string; tagNames?: string[]; collectionIds?: string[] }) =>
    request<Bookmark>('POST', '/api/bookmarks', b),
  updateBookmark: (id: string, patch: BookmarkPatch) =>
    request<Bookmark>('PATCH', `/api/bookmarks/${encodeURIComponent(id)}`, patch),
  trashBookmark: (id: string) => request<void>('DELETE', `/api/bookmarks/${encodeURIComponent(id)}`),
  restoreBookmark: (id: string) => request<Bookmark>('POST', `/api/bookmarks/${encodeURIComponent(id)}/restore`),
  deleteBookmarkPermanently: (id: string) =>
    request<void>('DELETE', `/api/bookmarks/${encodeURIComponent(id)}/permanent?confirm=true`),
  emptyTrash: () => request<{ deleted: number }>('DELETE', '/api/trash?confirm=true'),
  /** Irreversible: deletes the account and every bookmark, tag, collection and session it owns. */
  deleteAccount: (password: string) =>
    request<AccountDeletionSummary>('DELETE', '/api/account?confirm=true', { password }),
  /** Same-origin download URL; the session cookie authenticates the GET. */
  exportUrl: (format: 'json' | 'csv') => `/api/export?format=${format}`,
  refreshBookmark: (id: string) => request<Bookmark>('POST', `/api/bookmarks/${encodeURIComponent(id)}/refresh`),
  openBookmark: (id: string) => request<Bookmark>('POST', `/api/bookmarks/${encodeURIComponent(id)}/open`),
  tags: () => request<Tag[]>('GET', '/api/tags'),
  renameTag: (id: string, name: string) => request<Tag>('PATCH', `/api/tags/${encodeURIComponent(id)}`, { name }),
  deleteTag: (id: string) => request<void>('DELETE', `/api/tags/${encodeURIComponent(id)}`),
  mergeTag: (id: string, targetId: string) =>
    request<Tag>('POST', `/api/tags/${encodeURIComponent(id)}/merge`, { targetId }),
  collections: () => request<Collection[]>('GET', '/api/collections'),
  createCollection: (b: { name: string; description?: string }) => request<Collection>('POST', '/api/collections', b),
  updateCollection: (id: string, b: { name?: string; description?: string }) =>
    request<Collection>('PATCH', `/api/collections/${encodeURIComponent(id)}`, b),
  deleteCollection: (id: string) => request<void>('DELETE', `/api/collections/${encodeURIComponent(id)}`),
  /** Same-origin, owner-checked image proxy (the browser never contacts third-party hosts). */
  previewSrc: (b: { id: string; previewImageUrl: string | null; metadataFetchedAt: string | null }) =>
    b.previewImageUrl
      ? `/api/bookmarks/${encodeURIComponent(b.id)}/preview?v=${encodeURIComponent(b.metadataFetchedAt ?? '')}`
      : null,
};
