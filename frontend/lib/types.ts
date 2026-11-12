export type GroupId =
  | 'youtube' | 'products' | 'movies_series' | 'anime'
  | 'images' | 'memes' | 'articles' | 'social'
  | 'websites' | 'unsorted';

export type ProgressState =
  | 'none' | 'to_watch' | 'watching' | 'watched'
  | 'to_read' | 'reading' | 'read' | 'wishlist' | 'purchased';

export interface User {
  id: string;
  email: string;
  displayName: string;
  avatarUrl: string | null;
  createdAt: string;
}

export interface Classification {
  groupId: GroupId;
  platform: string;
  contentType: string;
  subtype: string | null;
  resourceId: string | null;
  confidence: 'high' | 'medium' | 'low';
  source: string;
  ruleId: string;
  ruleVersion: number;
  reason: string;
}

export interface ClassifyResult {
  interpretedUrl: string;
  schemeAdded: boolean;
  normalizedUrl: string;
  dedupeKey: string;
  classification: Classification;
}

export interface Bookmark {
  id: string;
  url: string;
  normalizedUrl: string;
  host: string;
  title: string;
  description: string | null;
  notes: string | null;
  groupId: GroupId;
  detectedGroupId: GroupId;
  groupOverridden: boolean;
  platform: string;
  contentType: string;
  subtype: string | null;
  classification: { confidence: string; source: string; ruleId: string; ruleVersion: number; reason: string };
  favorite: boolean;
  lifecycleState: 'active' | 'archived';
  progressState: ProgressState;
  deletedAt: string | null;
  metadataStatus: 'pending' | 'fetching' | 'ready' | 'partial' | 'failed' | 'blocked';
  metadataErrorCode: string | null;
  metadataFetchedAt: string | null;
  previewImageUrl: string | null;
  faviconUrl: string | null;
  siteName: string | null;
  resolvedUrl: string | null;
  titleEdited: boolean;
  descriptionEdited: boolean;
  createdAt: string;
  updatedAt: string;
  lastOpenedAt: string | null;
  openCount: number;
  tags: TagRef[];
  collections: CollectionRef[];
}

export interface TagRef { id: string; name: string; color: string | null }
export interface CollectionRef { id: string; name: string; color: string | null; icon: string | null }
export interface Tag extends TagRef { count: number }
export interface Collection extends CollectionRef { description: string | null; sortOrder: number; count: number }

export interface BookmarkPage {
  items: Bookmark[];
  nextCursor: string | null;
}

export interface GroupCount {
  id: GroupId;
  displayName: string;
  description: string;
  count: number;
  platforms: { platform: string; count: number }[];
}

export type View = 'active' | 'archived' | 'trash';
