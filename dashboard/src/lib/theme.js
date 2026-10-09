import { useEffect, useState } from 'react';

// Dark is the default; the choice is remembered in this browser only. Storage can be
// unavailable (private windows, blocked site data), so every access is guarded.
const STORAGE_KEY = 'theme';

export function storedTheme() {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

export function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  window.dispatchEvent(new Event('themechange'));
}

export function useTheme() {
  const [theme, setTheme] = useState(storedTheme);
  useEffect(() => {
    applyTheme(theme);
    try { localStorage.setItem(STORAGE_KEY, theme); } catch { /* not persisted */ }
  }, [theme]);
  return [theme, () => setTheme(t => (t === 'dark' ? 'light' : 'dark'))];
}

const COLOR_VARS = {
  allowed: '--accent-success',
  blocked: '--accent-danger',
  warning: '--accent-warning',
  accent: '--accent-primary',
  secondary: '--accent-secondary',
  cyan: '--accent-cyan',
  grid: '--chart-grid',
  axis: '--text-muted',
  text: '--text-secondary',
  surface: '--bg-card',
  border: '--border',
};

function readColors() {
  const css = getComputedStyle(document.documentElement);
  return Object.fromEntries(Object.entries(COLOR_VARS).map(([k, v]) => [k, css.getPropertyValue(v).trim()]));
}

/** Theme colours for charts, which take colours as values rather than CSS. */
export function useChartColors() {
  const [colors, setColors] = useState(readColors);
  useEffect(() => {
    const update = () => setColors(readColors());
    window.addEventListener('themechange', update);
    return () => window.removeEventListener('themechange', update);
  }, []);
  return colors;
}
