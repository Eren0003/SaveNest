import type { GroupId, ProgressState } from './types';

export const GROUP_LABELS: Record<GroupId, string> = {
  youtube: 'YouTube',
  products: 'Products',
  movies_series: 'Movies & Series',
  anime: 'Anime',
  images: 'Images',
  memes: 'Memes',
  articles: 'Articles',
  social: 'Social Posts',
  websites: 'Websites',
  unsorted: 'Unsorted',
};

export const GROUP_IDS = Object.keys(GROUP_LABELS) as GroupId[];

/** Short text glyph per group so badges don't rely on colour alone. */
export const GROUP_GLYPH: Record<GroupId, string> = {
  youtube: '▶', products: '🛒', movies_series: '🎬', anime: '✦', images: '🖼',
  memes: '☺', articles: '📄', social: '💬', websites: '🌐', unsorted: '•',
};

const PLATFORM_LABELS: Record<string, string> = {
  youtube: 'YouTube', amazon: 'Amazon', flipkart: 'Flipkart', myntra: 'Myntra', ajio: 'Ajio',
  meesho: 'Meesho', ebay: 'eBay', imdb: 'IMDb', tmdb: 'TMDB', myanimelist: 'MyAnimeList',
  anilist: 'AniList', reddit: 'Reddit', instagram: 'Instagram', generic: 'Generic',
};

export function platformLabel(p: string): string {
  return PLATFORM_LABELS[p.toLowerCase()] ?? p.charAt(0).toUpperCase() + p.slice(1);
}

export const PROGRESS_LABELS: Record<ProgressState, string> = {
  none: 'None', to_watch: 'To watch', watching: 'Watching', watched: 'Watched',
  to_read: 'To read', reading: 'Reading', read: 'Read', wishlist: 'Wishlist', purchased: 'Purchased',
};

/** Mirrors BookmarkService.validateProgress on the backend. */
export function progressOptions(contentType: string): ProgressState[] {
  if (['video', 'movie', 'series', 'anime', 'media_title'].includes(contentType))
    return ['none', 'to_watch', 'watching', 'watched'];
  if (['article', 'post'].includes(contentType)) return ['none', 'to_read', 'reading', 'read'];
  if (contentType === 'product') return ['none', 'wishlist', 'purchased'];
  return ['none'];
}

/** Deterministic placeholder hue from the host name. */
export function hostHue(host: string): number {
  let h = 0;
  for (let i = 0; i < host.length; i++) h = (h * 31 + host.charCodeAt(i)) % 360;
  return h;
}
