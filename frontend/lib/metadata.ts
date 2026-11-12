import type { Bookmark } from './types';

export const isEnriching = (b: Pick<Bookmark, 'metadataStatus'>) =>
  b.metadataStatus === 'pending' || b.metadataStatus === 'fetching';

const ERROR_TEXT: Record<string, string> = {
  access_denied: 'The site blocks automated previews.',
  blocked_target: 'This address cannot be previewed for security reasons.',
  rate_limited: 'The site is rate limiting requests.',
  not_found: 'The page was not found.',
  timeout: 'The site took too long to respond.',
  network: 'The site could not be reached.',
  server_error: 'The site returned an error.',
  too_large: 'The page is too large to preview.',
  unsupported_type: 'The link is not a previewable page or image.',
  too_many_redirects: 'The link redirects too many times.',
};

export function metadataLabel(b: Pick<Bookmark, 'metadataStatus' | 'metadataErrorCode'>): string {
  switch (b.metadataStatus) {
    case 'pending': return b.metadataErrorCode ? 'Preview retrying' : 'Preview pending';
    case 'fetching': return 'Fetching preview';
    case 'ready': return 'Preview ready';
    case 'partial': return 'Partial preview';
    case 'blocked': return 'Preview blocked';
    case 'failed': return 'Preview failed';
  }
}

export function metadataExplanation(b: Pick<Bookmark, 'metadataErrorCode'>): string | null {
  return b.metadataErrorCode ? ERROR_TEXT[b.metadataErrorCode] ?? 'Preview could not be fetched.' : null;
}
