import { useEffect, useState } from 'react';

// The gateway reports the caller's quota on every rate-limited response:
// X-RateLimit-Remaining, X-RateLimit-Reset (epoch ms) and, for costly requests,
// X-RateLimit-Cost; a 429 adds Retry-After. Recording them from responses the
// dashboard already makes shows the signed-in user's real usage without new calls.
let latest = null;
const listeners = new Set();

export function recordQuota(response) {
  const headers = response?.headers;
  const url = response?.config?.url || '';
  // /auth/* is limited per IP, not per user; its numbers are a different quota.
  if (!headers || url.startsWith('/auth')) return;
  const remaining = headers['x-ratelimit-remaining'];
  if (remaining === undefined) return;
  latest = {
    remaining: Number(remaining),
    resetMs: Number(headers['x-ratelimit-reset']) || null,
    cost: Number(headers['x-ratelimit-cost']) || 1,
    retryAfter: headers['retry-after'] ? Number(headers['retry-after']) : null,
    status: response.status,
    at: Date.now(),
  };
  listeners.forEach(listener => listener(latest));
}

export function useQuota() {
  const [quota, setQuota] = useState(latest);
  useEffect(() => {
    listeners.add(setQuota);
    return () => listeners.delete(setQuota);
  }, []);
  return quota;
}

/** Re-renders every second, for countdowns. */
export function useNow(intervalMs = 1000) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}
