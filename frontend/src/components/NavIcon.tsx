import type { ReactNode } from 'react';

const shapes = {
  runs: <><rect x="3" y="4" width="18" height="16" rx="3" /><path d="M3 10h18M9 10v10M15 10v10" /></>,
  guides: <><path d="M12 6C9 3 5 3 2 4v15c4-1 7-1 10 2 3-3 6-3 10-2V4c-3-1-7-1-10 2Z" /><path d="M12 6v15M5.5 8h3M15.5 8h3M5.5 12h3M15.5 12h3" /></>,
  mine: <><rect x="5" y="4" width="14" height="17" rx="3" /><path d="M9 3h6M9 10l2 2 4-4M9 16h6" /></>,
  profile: <><circle cx="12" cy="8" r="4" /><path d="M4 21v-2a8 8 0 0 1 16 0v2" /></>,
  schedule: <><rect x="3" y="5" width="18" height="16" rx="3" /><path d="M7 3v4M17 3v4M3 11h18M8 15h2M14 15h2M8 18h2" /></>,
  voice: <><rect x="9" y="2" width="6" height="13" rx="3" /><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8" /></>,
  players: <><circle cx="9" cy="7" r="3" /><path d="M2 21v-3a7 7 0 0 1 14 0v3M16 4a3 3 0 0 1 0 6M19 14a6 6 0 0 1 3 5v2" /></>,
  changelog: <><path d="M5 3h10l4 4v14H5ZM14 3v5h5M8 12h8M8 16h6" /></>,
  settings: <><path d="M4 6h16M4 12h16M4 18h16" /><circle cx="8" cy="6" r="2" fill="var(--bg-soft)" /><circle cx="16" cy="12" r="2" fill="var(--bg-soft)" /><circle cx="10" cy="18" r="2" fill="var(--bg-soft)" /></>,
  brand: <><path d="m12 2 3 7 7 3-7 3-3 7-3-7-7-3 7-3Z" /><circle cx="12" cy="12" r="2" /></>,
  phone: <><rect x="6" y="2" width="12" height="20" rx="3" /><path d="M10 18h4" /></>,
} satisfies Record<string, ReactNode>;

export type NavIconName = keyof typeof shapes;

/** Navigation uses one stroke style and inherits the link's foreground color. */
export default function NavIcon({ name }: { name: NavIconName }) {
  return <svg className="nav-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">{shapes[name]}</svg>;
}
