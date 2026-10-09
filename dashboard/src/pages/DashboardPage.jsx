import { useEffect, useMemo, useRef, useState } from 'react';
import { analyticsApi, rulesApi, demoApi, errorMessage } from '../services/api';
import { useAuth } from '../context/AuthContext';
import { RANGES, RANGE_KEYS, toChartPoints } from '../lib/timeseries';
import { useChartColors } from '../lib/theme';
import { useQuota, useNow } from '../lib/quota';
import {
  Activity, ShieldAlert, ShieldCheck, Gauge, Send, Zap, Play, Square, Clock,
  Pencil, AlertCircle, Server, ListOrdered,
} from 'lucide-react';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { format } from 'date-fns';

// Every figure on this page comes from the gateway and the admin API: summary and
// time series by polling, the request log from the live event stream, and the quota
// from the X-RateLimit-* headers on the dashboard's own requests.

const REFRESH_MS = 15000;
const RECONNECT_MS = 5000;
const LOG_SIZE = 50;
const WARN_AT = 0.8;
// The gateway's limit for a tenant and tier with no rule (RateLimiterService).
const DEFAULT_RULE = { algorithm: 'SLIDING_WINDOW', requestLimit: 60, windowMs: 60000, burstCapacity: 60, isDefault: true };

const ENDPOINTS = [
  { path: '/demo/ping', label: 'GET /demo/ping', cost: 1 },
  { path: '/demo/echo', label: 'GET /demo/echo', cost: 1 },
  { path: '/demo/slow', label: 'GET /demo/slow', cost: 5 },
];

const fmt = n => (n == null || Number.isNaN(n) ? '—' : Number(n).toLocaleString());

function statusClass(code) {
  if (code === 429) return 's429';
  if (code >= 500) return 's5xx';
  if (code >= 400) return 's4xx';
  return 's2xx';
}

function StatusBadge({ code }) {
  return <span className={`status ${statusClass(code)}`}>{code ?? '—'}</span>;
}

function quotaLevel(used, limit) {
  if (limit <= 0) return '';
  const share = used / limit;
  return share >= 1 ? 'full' : share >= WARN_AT ? 'warn' : '';
}

// ─── Building blocks ─────────────────────────────────────────────────────────

function StatCard({ label, value, foot, icon: Icon, tone, loading, children }) {
  return (
    <div className="kpi-card">
      <div className="kpi-top">
        <span className="kpi-label">{label}</span>
        <span className={`kpi-icon-wrap ${tone}`}><Icon /></span>
      </div>
      <div className="kpi-value">{loading ? <span className="skeleton" style={{ width: 90 }} /> : value}</div>
      {children}
      {foot && <div className="kpi-foot">{foot}</div>}
    </div>
  );
}

function ChartTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null;
  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 'var(--radius)', padding: '8px 12px', fontSize: '12px', boxShadow: 'var(--shadow)' }}>
      <div style={{ color: 'var(--text-muted)', marginBottom: 4 }}>{label}</div>
      {payload.map(p => (
        <div key={p.dataKey} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ width: 8, height: 8, borderRadius: 2, background: p.color }} />
          <span style={{ color: 'var(--text-secondary)' }}>{p.name}</span>
          <span className="num" style={{ marginLeft: 'auto', paddingLeft: 12, fontWeight: 600 }}>{p.value}</span>
        </div>
      ))}
    </div>
  );
}

function TrafficChart({ series, range, onRange, colors }) {
  const data = toChartPoints(series, range);
  return (
    <div className="chart-container">
      <div className="card-header">
        <div>
          <div className="card-title"><Activity size={15} />Requests over time</div>
          <div className="card-sub" style={{ display: 'flex', gap: 14, marginTop: 4 }}>
            <span><span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 2, background: colors.allowed, marginRight: 6 }} />Allowed</span>
            <span><span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 2, background: colors.blocked, marginRight: 6 }} />Blocked</span>
            <span className="hide-sm">per {RANGES[range].bucket}</span>
          </div>
        </div>
        <div className="segmented" role="tablist" aria-label="Time range">
          {RANGE_KEYS.map(r => (
            <button key={r} className={range === r ? 'active' : ''} onClick={() => onRange(r)} id={`range-${r}`}>{r}</button>
          ))}
        </div>
      </div>
      <ResponsiveContainer width="100%" height={240}>
        <AreaChart data={data} margin={{ top: 4, right: 4, left: -12, bottom: 0 }}>
          <defs>
            <linearGradient id="fillAllowed" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={colors.allowed} stopOpacity={0.28} />
              <stop offset="100%" stopColor={colors.allowed} stopOpacity={0} />
            </linearGradient>
            <linearGradient id="fillBlocked" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={colors.blocked} stopOpacity={0.3} />
              <stop offset="100%" stopColor={colors.blocked} stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid stroke={colors.grid} vertical={false} />
          <XAxis dataKey="time" stroke={colors.axis} tick={{ fontSize: 11 }} tickLine={false} axisLine={false} minTickGap={28} />
          <YAxis stroke={colors.axis} tick={{ fontSize: 11 }} tickLine={false} axisLine={false} allowDecimals={false} width={44} />
          <Tooltip content={<ChartTooltip />} cursor={{ stroke: colors.border }} />
          <Area type="monotone" dataKey="allowed" name="Allowed" stroke={colors.allowed} fill="url(#fillAllowed)" strokeWidth={2} dot={false} isAnimationActive={false} />
          <Area type="monotone" dataKey="blocked" name="Blocked" stroke={colors.blocked} fill="url(#fillBlocked)" strokeWidth={2} dot={false} isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

/** Sends ordinary requests through the gateway as the signed-in user. */
function Playground() {
  const [endpoint, setEndpoint] = useState(ENDPOINTS[0].path);
  const [busy, setBusy] = useState(null);       // 'one' | 'burst' while in flight
  const [auto, setAuto] = useState(false);
  const [last, setLast] = useState(null);
  const autoRef = useRef(null);

  const sendOne = async () => {
    const started = performance.now();
    try {
      const res = await demoApi.send(endpoint);
      return { status: res.status, ms: Math.round(performance.now() - started) };
    } catch (err) {
      return { status: err.response?.status ?? 0, ms: Math.round(performance.now() - started) };
    }
  };

  const single = async () => {
    setBusy('one');
    const result = await sendOne();
    setLast({ ...result, label: '1 request' });
    setBusy(null);
  };

  const burst = async () => {
    setBusy('burst');
    const results = await Promise.all(Array.from({ length: 10 }, sendOne));
    const allowed = results.filter(r => r.status >= 200 && r.status < 300).length;
    setLast({ status: allowed === 10 ? 200 : 429, ms: Math.max(...results.map(r => r.ms)), label: `${allowed} allowed, ${10 - allowed} blocked` });
    setBusy(null);
  };

  useEffect(() => {
    if (!auto) return undefined;
    autoRef.current = setInterval(async () => {
      const result = await sendOne();
      setLast({ ...result, label: 'auto, 1 per second' });
    }, 1000);
    return () => clearInterval(autoRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auto, endpoint]);

  const cost = ENDPOINTS.find(e => e.path === endpoint)?.cost ?? 1;

  return (
    <div className="panel">
      <div className="card-header" style={{ marginBottom: 12 }}>
        <div>
          <div className="card-title"><Send size={15} />Request playground</div>
          <div className="card-sub">Requests count against your own limit, like any client's</div>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <select value={endpoint} onChange={e => setEndpoint(e.target.value)} style={{ width: 'auto', minWidth: 170, fontFamily: 'var(--font-mono)', fontSize: 12.5 }} id="playground-endpoint" aria-label="Endpoint">
          {ENDPOINTS.map(e => <option key={e.path} value={e.path}>{e.label}{e.cost > 1 ? ` (cost ${e.cost})` : ''}</option>)}
        </select>
        <button className="btn btn-primary" onClick={single} disabled={!!busy} id="playground-send">
          {busy === 'one' ? <span className="spinner" /> : <Send size={14} />}Send
        </button>
        <button className="btn btn-secondary" onClick={burst} disabled={!!busy} id="playground-burst">
          {busy === 'burst' ? <span className="spinner" /> : <Zap size={14} />}Burst ×10
        </button>
        <button className={`btn ${auto ? 'btn-danger' : 'btn-secondary'}`} onClick={() => setAuto(a => !a)} id="playground-auto">
          {auto ? <Square size={13} /> : <Play size={13} />}{auto ? 'Stop' : 'Auto'}
        </button>
      </div>
      <div style={{ marginTop: 12, fontSize: 12.5, color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: 10, minHeight: 22, flexWrap: 'wrap' }}>
        {last ? (
          <>
            <StatusBadge code={last.status} />
            <span>{last.label}</span>
            <span className="num">{last.ms} ms</span>
          </>
        ) : (
          <span>Each request costs {cost} unit{cost > 1 ? 's' : ''} of your limit.</span>
        )}
      </div>
    </div>
  );
}

function RequestLog({ events, connection }) {
  const [filter, setFilter] = useState('all');
  const shown = events.filter(e =>
    filter === 'all' ? true : filter === 'blocked' ? e.status === 'BLOCKED' : e.status !== 'BLOCKED');

  return (
    <div className="panel">
      <div className="card-header">
        <div className="card-title">
          <ListOrdered size={15} />Live request log
          <span className="pill" style={{ marginLeft: 4 }}>
            <span className={`live-dot ${connection === 'connected' ? '' : 'off'}`} />
            {connection === 'connected' ? 'Live' : connection === 'connecting' ? 'Connecting' : 'Reconnecting'}
          </span>
        </div>
        <div className="segmented" aria-label="Filter">
          {['all', 'allowed', 'blocked'].map(f => (
            <button key={f} className={filter === f ? 'active' : ''} onClick={() => setFilter(f)}>{f[0].toUpperCase() + f.slice(1)}</button>
          ))}
        </div>
      </div>
      {shown.length === 0 ? (
        <div className="empty-state">
          <Activity />
          <h3>{events.length ? 'Nothing matches this filter' : 'No traffic yet'}</h3>
          <p>Requests through the gateway appear here as they happen. Try the playground.</p>
        </div>
      ) : (
        <div className="log-wrap log-scroll">
          <table className="log-table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Request</th>
                <th className="hide-sm">Tenant</th>
                <th>Status</th>
                <th className="right hide-sm">Latency</th>
              </tr>
            </thead>
            <tbody>
              {shown.map(evt => (
                <tr key={evt._id} className={evt._fresh ? 'row-new' : ''}>
                  <td className="num">{evt.timestamp ? format(new Date(evt.timestamp), 'HH:mm:ss') : '—'}</td>
                  <td><span className="log-method">{evt.method}</span><span className="log-path">{evt.path}</span></td>
                  <td className="hide-sm"><span className="badge gray">{evt.tenantId}</span></td>
                  <td><StatusBadge code={evt.httpStatus || (evt.status === 'BLOCKED' ? 429 : 200)} /></td>
                  <td className="num right hide-sm">{evt.latencyMs} ms</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function RingGauge({ used, limit, level }) {
  const r = 70;
  const circumference = 2 * Math.PI * r;
  const share = limit > 0 ? Math.min(1, used / limit) : 0;
  return (
    <div className={`gauge ${level}`}>
      <svg viewBox="0 0 168 168" aria-hidden="true">
        <circle className="gauge-track" cx="84" cy="84" r={r} fill="none" strokeWidth="12" />
        <circle className="gauge-fill" cx="84" cy="84" r={r} fill="none" strokeWidth="12" strokeLinecap="round"
          strokeDasharray={circumference} strokeDashoffset={circumference * (1 - share)} />
      </svg>
      <div className="gauge-center">
        <div className="gauge-value">{Math.round(share * 100)}%</div>
        <div className="gauge-caption">of limit used</div>
      </div>
    </div>
  );
}

/** The signed-in user's limit: live usage from response headers, and the rule behind it. */
function QuotaPanel({ rule, quota, now, canEdit, onSaved }) {
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const tokenBucket = rule.algorithm === 'TOKEN_BUCKET';
  const limit = tokenBucket ? rule.burstCapacity : rule.requestLimit;
  const windowCleared = !tokenBucket && quota?.resetMs && now >= quota.resetMs;
  const remaining = quota == null ? null : windowCleared ? limit : Math.min(limit, quota.remaining);
  const used = remaining == null ? 0 : Math.max(0, limit - remaining);
  const level = remaining == null ? '' : quotaLevel(used, limit);
  const secondsLeft = quota?.resetMs ? Math.max(0, Math.ceil((quota.resetMs - now) / 1000)) : null;

  const startEdit = () => {
    setForm({ algorithm: rule.algorithm, requestLimit: rule.requestLimit, windowSeconds: rule.windowMs / 1000, burstCapacity: rule.burstCapacity });
    setError('');
    setEditing(true);
  };

  const save = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError('');
    try {
      await rulesApi.update(rule.id, {
        ...rule,
        algorithm: form.algorithm,
        requestLimit: Number(form.requestLimit),
        windowMs: Math.round(Number(form.windowSeconds) * 1000),
        burstCapacity: Number(form.burstCapacity),
      });
      setEditing(false);
      onSaved();
    } catch (err) {
      setError(errorMessage(err, 'Could not save the rule'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="panel">
      <div className="card-header">
        <div className="card-title"><Gauge size={15} />Your rate limit</div>
        {canEdit && !editing && (
          <button className="btn btn-secondary btn-sm" onClick={startEdit} id="edit-limit"><Pencil size={12} />Edit</button>
        )}
      </div>

      <RingGauge used={used} limit={limit} level={level} />

      <div className="countdown">
        <Clock size={13} />
        {quota == null ? <span>Waiting for your first request</span>
          : quota.status === 429 && quota.retryAfter && secondsLeft > 0 ? <span>Blocked, retry in <span className="num">{secondsLeft}s</span></span>
          : windowCleared ? <span>Window clear, full quota available</span>
          : <span>{tokenBucket ? 'Next token in' : 'Window clears in'} <span className="num">{secondsLeft ?? '—'}s</span></span>}
      </div>

      {!editing ? (
        <div className="meta-list">
          <div className="meta-row"><span>Remaining</span><span>{remaining == null ? '—' : `${fmt(remaining)} / ${fmt(limit)}`}</span></div>
          <div className="meta-row"><span>Algorithm</span><span>{tokenBucket ? 'Token bucket' : 'Sliding window'}</span></div>
          <div className="meta-row"><span>Limit</span><span>{fmt(rule.requestLimit)} / {rule.windowMs / 1000}s</span></div>
          {tokenBucket && <div className="meta-row"><span>Burst</span><span>{fmt(rule.burstCapacity)}</span></div>}
          <div className="meta-row"><span>Rule</span><span>{rule.isDefault ? 'Gateway default' : `${rule.tenantId} · ${rule.tier}`}</span></div>
        </div>
      ) : (
        <form onSubmit={save}>
          {error && <div className="alert error"><AlertCircle size={14} />{error}</div>}
          <div className="input-group">
            <label htmlFor="rule-algorithm">Algorithm</label>
            <select id="rule-algorithm" value={form.algorithm} onChange={e => setForm(f => ({ ...f, algorithm: e.target.value }))}>
              <option value="SLIDING_WINDOW">Sliding window</option>
              <option value="TOKEN_BUCKET">Token bucket</option>
            </select>
          </div>
          <div className="field-row">
            <div className="input-group">
              <label htmlFor="rule-limit">Requests</label>
              <input id="rule-limit" type="number" min="1" required value={form.requestLimit} onChange={e => setForm(f => ({ ...f, requestLimit: e.target.value }))} />
            </div>
            <div className="input-group">
              <label htmlFor="rule-window">Per seconds</label>
              <input id="rule-window" type="number" min="1" required value={form.windowSeconds} onChange={e => setForm(f => ({ ...f, windowSeconds: e.target.value }))} />
            </div>
          </div>
          <div className="input-group">
            <label htmlFor="rule-burst">Burst capacity</label>
            <input id="rule-burst" type="number" min="1" required value={form.burstCapacity} onChange={e => setForm(f => ({ ...f, burstCapacity: e.target.value }))} />
          </div>
          <div className="card-sub" style={{ marginBottom: 12 }}>The gateway picks up changes within 30 seconds.</div>
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => setEditing(false)} disabled={saving}>Cancel</button>
            <button type="submit" className="btn btn-primary btn-sm" disabled={saving} id="save-limit">
              {saving && <span className="spinner" />}Save
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

function TopBlocked({ summary, error }) {
  const list = summary?.topBlockedTenants || [];
  const max = Math.max(1, ...list.map(t => t.blockedCount));
  return (
    <div className="panel">
      <div className="card-header"><div className="card-title"><ShieldAlert size={15} />Most blocked tenants</div><span className="card-sub">24 h</span></div>
      {!summary ? (
        error ? <div className="card-sub">Unavailable</div> : <div className="loading-overlay" style={{ padding: 16 }}><div className="spinner" /></div>
      ) : list.length === 0 ? (
        <div className="card-sub">No requests were rate limited in the last 24 hours.</div>
      ) : (
        <div className="bar-list">
          {list.map(t => (
            <div key={t.tenantId}>
              <div className="bar-row-head"><span>{t.tenantId}</span><span>{fmt(t.blockedCount)}</span></div>
              <div className="bar-track"><span style={{ width: `${(t.blockedCount / max) * 100}%` }} /></div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function GatewayPanel({ summary }) {
  return (
    <div className="panel">
      <div className="card-header"><div className="card-title"><Server size={15} />Gateway</div><span className="card-sub">24 h</span></div>
      <div className="meta-list">
        <div className="meta-row"><span>Average latency</span><span>{summary ? `${summary.avgLatencyMs.toFixed(1)} ms` : '—'}</span></div>
        <div className="meta-row"><span>Block rate</span><span>{summary ? `${summary.blockRate.toFixed(2)}%` : '—'}</span></div>
        <div className="meta-row"><span>Active tenants</span><span>{summary ? fmt(summary.activeTenants) : '—'}</span></div>
      </div>
    </div>
  );
}

// ─── Page ────────────────────────────────────────────────────────────────────

export default function DashboardPage() {
  const { user } = useAuth();
  const colors = useChartColors();
  const quota = useQuota();
  const now = useNow();

  const [summary, setSummary] = useState(null);
  const [summaryError, setSummaryError] = useState('');
  const [range, setRange] = useState('1h');
  const [series, setSeries] = useState([]);
  const [seriesError, setSeriesError] = useState('');
  const [events, setEvents] = useState([]);
  const [connection, setConnection] = useState('connecting');
  const [rules, setRules] = useState(null);
  const nextId = useRef(0);

  // Summary figures cover the last 24 hours, whatever range the chart shows.
  useEffect(() => {
    const load = () => analyticsApi.getSummary()
      .then(res => { setSummary(res.data); setSummaryError(''); })
      .catch(err => setSummaryError(errorMessage(err, 'Could not load the traffic summary')));
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    const load = () => analyticsApi.getTimeSeries(undefined, range)
      .then(res => { setSeries(res.data); setSeriesError(''); })
      .catch(err => setSeriesError(errorMessage(err, 'Could not load traffic over time')));
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [range]);

  const loadRules = () => rulesApi.getAll()
    .then(res => setRules(res.data.data || []))
    .catch(() => setRules([]));
  useEffect(() => { loadRules(); }, []);

  // The log starts from the latest stored events, then follows the live stream. The
  // server sends named events ("connected", "trafficEvent"), so they are listened for
  // by name; onmessage alone never received any of them.
  useEffect(() => {
    const tag = (evt, fresh) => ({ ...evt, _id: nextId.current++, _fresh: fresh });
    analyticsApi.getRecentEvents(undefined, 0, LOG_SIZE)
      .then(res => setEvents(prev => [...prev, ...res.data.content.map(e => tag(e, false))].slice(0, LOG_SIZE)))
      .catch(() => {});

    let source;
    let retryTimer;
    let stopped = false;
    const connect = () => {
      // EventSource cannot send headers, so the token goes in the query string. It is
      // read on every attempt so a reconnect picks up a refreshed token.
      const token = localStorage.getItem('access_token');
      source = new EventSource(`/api/admin/analytics/live${token ? `?token=${encodeURIComponent(token)}` : ''}`);
      source.addEventListener('connected', () => setConnection('connected'));
      source.addEventListener('trafficEvent', e => {
        try {
          setEvents(prev => [tag(JSON.parse(e.data), true), ...prev].slice(0, LOG_SIZE));
        } catch { /* ignore a malformed event */ }
      });
      source.onerror = () => {
        source.close();
        if (!stopped) {
          setConnection('reconnecting');
          retryTimer = setTimeout(connect, RECONNECT_MS);
        }
      };
    };
    connect();
    return () => {
      stopped = true;
      source?.close();
      clearTimeout(retryTimer);
    };
  }, []);

  const rule = useMemo(() => {
    const own = (rules || []).find(r => r.active && r.tenantId === user?.tenantId && r.tier === user?.tier);
    return own || DEFAULT_RULE;
  }, [rules, user]);

  const canEdit = !rule.isDefault && (user?.roles || []).some(r => r === 'ROLE_ADMIN' || r === 'ROLE_SUPER_ADMIN');
  const tokenBucket = rule.algorithm === 'TOKEN_BUCKET';
  const limit = tokenBucket ? rule.burstCapacity : rule.requestLimit;
  const windowCleared = !tokenBucket && quota?.resetMs && now >= quota.resetMs;
  const remaining = quota == null ? null : windowCleared ? limit : Math.min(limit, quota.remaining);
  const usedShare = remaining == null ? 0 : Math.max(0, limit - remaining) / limit;
  const level = remaining == null ? '' : quotaLevel(limit - remaining, limit);
  const loading = !summary && !summaryError;
  const allowed = summary ? summary.totalRequests - summary.blockedRequests : null;

  return (
    <div className="page-content">
      <div className="page-head">
        <div>
          <h1 className="section-title">Overview</h1>
          <p className="section-subtitle">Gateway traffic and rate limiting. Cards cover the last 24 hours.</p>
        </div>
      </div>

      {summaryError && <div className="alert error"><AlertCircle size={14} />{summaryError}</div>}
      {seriesError && <div className="alert error"><AlertCircle size={14} />{seriesError}</div>}

      <div className="kpi-grid">
        <StatCard label="Total requests" value={fmt(summary?.totalRequests)} foot="Last 24 hours" icon={Activity} tone="blue" loading={loading} />
        <StatCard label="Allowed" value={fmt(allowed)} icon={ShieldCheck} tone="green" loading={loading}
          foot={summary && summary.totalRequests > 0 ? `${((allowed / summary.totalRequests) * 100).toFixed(1)}% of requests` : 'Last 24 hours'} />
        <StatCard label="Blocked" value={fmt(summary?.blockedRequests)} icon={ShieldAlert} tone="red" loading={loading}
          foot={summary ? `${summary.blockRate.toFixed(2)}% block rate` : 'Last 24 hours'} />
        <StatCard label="Your remaining quota" icon={Gauge} tone={level === 'full' ? 'red' : level === 'warn' ? 'orange' : 'green'}
          value={remaining == null ? '—' : <>{fmt(remaining)}<span style={{ fontSize: '0.55em', color: 'var(--text-muted)' }}> / {fmt(limit)}</span></>}
          foot={remaining == null ? 'Shown after your first request' : tokenBucket ? 'Tokens in your bucket' : 'In the current window'}>
          <div className={`progress ${level}`} style={{ marginTop: 10 }}><span style={{ width: `${usedShare * 100}%` }} /></div>
        </StatCard>
      </div>

      <div className="dashboard-grid">
        <div className="stack">
          <TrafficChart series={series} range={range} onRange={setRange} colors={colors} />
          <Playground />
          <RequestLog events={events} connection={connection} />
        </div>
        <div className="stack dashboard-aside">
          <QuotaPanel rule={rule} quota={quota} now={now} canEdit={canEdit} onSaved={loadRules} />
          <TopBlocked summary={summary} error={summaryError} />
          <GatewayPanel summary={summary} />
        </div>
      </div>
    </div>
  );
}
