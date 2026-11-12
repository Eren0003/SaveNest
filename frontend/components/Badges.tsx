import type { GroupId } from '@/lib/types';
import { GROUP_GLYPH, GROUP_LABELS, platformLabel } from '@/lib/labels';

export function GroupBadge({ id }: { id: GroupId }) {
  return (
    <span className="badge">
      <span aria-hidden="true" className="mr-1">{GROUP_GLYPH[id]}</span>
      {GROUP_LABELS[id] ?? id}
    </span>
  );
}

export function PlatformBadge({ platform }: { platform: string }) {
  return <span className="badge bg-slate-100 dark:bg-slate-800">{platformLabel(platform)}</span>;
}
