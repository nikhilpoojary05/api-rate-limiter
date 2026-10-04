import { useState, useEffect } from 'react';
import { analyticsApi, errorMessage } from '../services/api';
import { RANGES, RANGE_KEYS, toChartPoints } from '../lib/timeseries';
import { Activity, ShieldAlert, Clock, Users, Zap, AlertCircle } from 'lucide-react';
import {
  AreaChart, Area, LineChart, Line, BarChart, Bar,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend
} from 'recharts';

// Everything on this page comes from the admin API. It used to fill gaps with sample
// events, random chart points and fixed trend figures, which looked live with no
// traffic at all.

const REFRESH_MS = 15000;
const RECONNECT_MS = 5000;
const FEED_SIZE = 50;

function KpiCard({ label, value, icon: Icon, color, suffix = '' }) {
  return (
    <div className={`kpi-card ${color}`}>
      <div className={`kpi-icon-wrap ${color}`}>
        <Icon />
      </div>
      <div className="kpi-value">{value}{value === '—' ? '' : suffix}</div>
      <div className="kpi-label">{label}</div>
    </div>
  );
}

function LiveEventFeed({ events, connection }) {
  const connected = connection === 'connected';
  return (
    <div className="live-feed">
      <div className="live-feed-header">
        {connected && <span className="live-dot" />}
        Live Traffic Feed
        <span style={{ marginLeft: 'auto', fontSize: '11px', color: 'var(--text-muted)' }}>
          {connected ? 'Live' : connection === 'connecting' ? 'Connecting…' : 'Reconnecting…'}
        </span>
      </div>
      <div className="live-feed-list">
        {events.length === 0 ? (
          <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '12px' }}>
            No traffic yet. Requests through the gateway appear here as they happen.
          </div>
        ) : (
          events.map((evt, i) => (
            <div key={i} className="live-event">
              <div className={`live-event-status ${evt.status?.toLowerCase()}`} />
              <span className="live-event-path">{evt.method} {evt.path}</span>
              <span className="live-event-tenant">{evt.tenantId}</span>
              <span className="live-event-latency">{evt.latencyMs}ms</span>
              <span className={`badge ${evt.status === 'BLOCKED' ? 'red' : 'green'}`} style={{ fontSize: '10px', padding: '1px 5px' }}>
                {evt.status}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

const CustomTooltip = ({ active, payload, label }) => {
  if (active && payload && payload.length) {
    return (
      <div style={{
        background: 'var(--bg-elevated)', border: '1px solid var(--border)',
        borderRadius: 'var(--radius)', padding: '10px 14px', fontSize: '12px'
      }}>
        <p style={{ color: 'var(--text-muted)', marginBottom: '6px' }}>{label}</p>
        {payload.map((p, i) => (
          <p key={i} style={{ color: p.color, fontWeight: 600 }}>
            {p.name}: {p.value ?? '—'}
          </p>
        ))}
      </div>
    );
  }
  return null;
};

function ErrorBanner({ message }) {
  return (
    <div style={{
      background: 'var(--accent-danger-glow)', border: '1px solid rgba(239,68,68,0.3)',
      borderRadius: 'var(--radius)', padding: '10px 12px', color: 'var(--accent-danger)',
      fontSize: '13px', marginBottom: '16px', display: 'flex', gap: '8px', alignItems: 'center'
    }}>
      <AlertCircle size={14} />{message}
    </div>
  );
}

export default function DashboardPage() {
  const [summary, setSummary] = useState(null);
  const [summaryError, setSummaryError] = useState('');
  const [range, setRange] = useState('1h');
  const [series, setSeries] = useState([]);
  const [seriesError, setSeriesError] = useState('');
  const [events, setEvents] = useState([]);
  const [connection, setConnection] = useState('connecting');

  // Summary figures cover the last 24 hours, whatever range the charts show.
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

  // The feed starts from the latest stored events, then follows the live stream. The
  // server sends named events ("connected", "trafficEvent"); listening on onmessage
  // only, as before, never received any of them.
  useEffect(() => {
    analyticsApi.getRecentEvents(undefined, 0, FEED_SIZE)
      .then(res => setEvents(prev => [...prev, ...res.data.content].slice(0, FEED_SIZE)))
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
          const evt = JSON.parse(e.data);
          setEvents(prev => [evt, ...prev].slice(0, FEED_SIZE));
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

  const chartData = toChartPoints(series, range);
  const bucket = RANGES[range].bucket;
  const fmt = (n, digits = 0) => summary ? n.toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: digits }) : '—';

  return (
    <div className="page-content">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px' }}>
        <div>
          <h1 className="section-title">Overview Dashboard</h1>
          <p className="section-subtitle">Gateway traffic and rate limiting. Cards cover the last 24 hours; charts follow the selected range.</p>
        </div>
        <div style={{ display: 'flex', gap: '8px' }}>
          {RANGE_KEYS.map(r => (
            <button key={r} className={`btn btn-sm ${range === r ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => setRange(r)} id={`range-${r}`}>
              {r}
            </button>
          ))}
        </div>
      </div>

      {summaryError && <ErrorBanner message={summaryError} />}
      {seriesError && <ErrorBanner message={seriesError} />}

      {/* KPI Cards */}
      <div className="kpi-grid">
        <KpiCard label="Total Requests (24h)" value={fmt(summary?.totalRequests)} icon={Activity} color="blue" />
        <KpiCard label="Blocked Requests (24h)" value={fmt(summary?.blockedRequests)} icon={ShieldAlert} color="red" />
        <KpiCard label="Block Rate (24h)" value={fmt(summary?.blockRate, 2)} icon={Zap} color="orange" suffix="%" />
        <KpiCard label="Avg Latency (24h)" value={fmt(summary?.avgLatencyMs, 1)} icon={Clock} color="purple" suffix="ms" />
        <KpiCard label="Active Tenants" value={fmt(summary?.activeTenants)} icon={Users} color="green" />
      </div>

      {/* Traffic Chart + Live Feed */}
      <div className="grid-cols-2-1" style={{ marginBottom: '16px' }}>
        <div className="chart-container">
          <div className="card-header">
            <span className="card-title"><Activity size={16} />Traffic Volume ({range})</span>
            <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>Requests per {bucket}</span>
          </div>
          <ResponsiveContainer width="100%" height={240}>
            <AreaChart data={chartData}>
              <defs>
                <linearGradient id="gradAllowed" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3} />
                  <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
                </linearGradient>
                <linearGradient id="gradBlocked" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#ef4444" stopOpacity={0.3} />
                  <stop offset="95%" stopColor="#ef4444" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" vertical={false} />
              <XAxis dataKey="time" stroke="var(--text-muted)" tick={{ fontSize: 11 }} minTickGap={24} />
              <YAxis stroke="var(--text-muted)" tick={{ fontSize: 11 }} allowDecimals={false} />
              <Tooltip content={<CustomTooltip />} />
              <Legend wrapperStyle={{ fontSize: '12px', color: 'var(--text-secondary)' }} />
              <Area type="monotone" dataKey="allowed" name="Allowed" stroke="#3b82f6" fill="url(#gradAllowed)" strokeWidth={2} dot={false} />
              <Area type="monotone" dataKey="blocked" name="Blocked" stroke="#ef4444" fill="url(#gradBlocked)" strokeWidth={2} dot={false} />
            </AreaChart>
          </ResponsiveContainer>
        </div>
        <LiveEventFeed events={events} connection={connection} />
      </div>

      {/* Latency Chart + Top Blocked */}
      <div className="grid-2">
        <div className="chart-container">
          <div className="card-header">
            <span className="card-title"><Clock size={16} />Response Latency ({range})</span>
            <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>Average time to first byte (ms) per {bucket}</span>
          </div>
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" vertical={false} />
              <XAxis dataKey="time" stroke="var(--text-muted)" tick={{ fontSize: 11 }} minTickGap={24} />
              <YAxis stroke="var(--text-muted)" tick={{ fontSize: 11 }} />
              <Tooltip content={<CustomTooltip />} />
              <Line type="monotone" dataKey="latency" name="Latency (ms)" stroke="#8b5cf6" strokeWidth={2} dot={false} connectNulls={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>

        <div className="chart-container">
          <div className="card-header">
            <span className="card-title"><ShieldAlert size={16} />Top Blocked Tenants (24h)</span>
          </div>
          {!summary ? (
            summaryError
              ? <div className="empty-state"><p>Unavailable</p></div>
              : <div className="loading-overlay"><div className="spinner" /></div>
          ) : summary.topBlockedTenants.length === 0 ? (
            <div className="empty-state">
              <ShieldAlert />
              <h3>Nothing blocked</h3>
              <p>No requests were rate limited in the last 24 hours</p>
            </div>
          ) : (
            <ResponsiveContainer width="100%" height={200}>
              <BarChart data={summary.topBlockedTenants} layout="vertical">
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" horizontal={false} />
                <XAxis type="number" stroke="var(--text-muted)" tick={{ fontSize: 11 }} allowDecimals={false} />
                <YAxis type="category" dataKey="tenantId" stroke="var(--text-muted)" tick={{ fontSize: 11 }} width={90} />
                <Tooltip content={<CustomTooltip />} />
                <Bar dataKey="blockedCount" name="Blocked" fill="#ef4444" radius={[0, 4, 4, 0]} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>
    </div>
  );
}
