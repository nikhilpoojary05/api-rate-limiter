import { format } from 'date-fns';

/**
 * The ranges the analytics API accepts, with the bucket width it uses for each
 * (see TrafficEventService.Range) and how to label the time axis.
 */
export const RANGES = {
  '1h': { bucket: '1 min', axis: 'HH:mm' },
  '6h': { bucket: '5 min', axis: 'HH:mm' },
  '24h': { bucket: '30 min', axis: 'HH:mm' },
  '7d': { bucket: '6 h', axis: 'dd MMM HH:mm' },
};

export const RANGE_KEYS = Object.keys(RANGES);

/**
 * API points to chart points. Latency is left empty for buckets with no requests, so
 * the line breaks there instead of dropping to a misleading 0 ms.
 */
export function toChartPoints(series, range) {
  return series.map(p => ({
    time: format(new Date(p.timestamp), RANGES[range].axis),
    allowed: p.allowed,
    blocked: p.blocked,
    latency: p.allowed + p.blocked > 0 ? Math.round(p.avgLatency * 10) / 10 : null,
  }));
}

/** Totals across a series; latency is weighted by request count, not averaged per bucket. */
export function totals(series) {
  let allowed = 0, blocked = 0, latencyWeighted = 0;
  for (const p of series) {
    allowed += p.allowed;
    blocked += p.blocked;
    latencyWeighted += p.avgLatency * (p.allowed + p.blocked);
  }
  const requests = allowed + blocked;
  return {
    allowed,
    blocked,
    blockRate: requests > 0 ? (blocked / requests) * 100 : 0,
    avgLatency: requests > 0 ? latencyWeighted / requests : 0,
  };
}
