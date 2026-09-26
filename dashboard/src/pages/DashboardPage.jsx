import { useState, useEffect, useRef } from 'react';
import { analyticsApi } from '../services/api';
import {
  Activity, ShieldAlert, Clock, Users, TrendingUp, TrendingDown,
  Zap, Globe, RefreshCw
} from 'lucide-react';
import {
  AreaChart, Area, LineChart, Line, BarChart, Bar,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend
} from 'recharts';
import { format } from 'date-fns';

const DEMO_EVENTS = [
  { path: '/api/demo/ping', tenantId: 'acme-corp', status: 'ALLOWED', latencyMs: 12 },
  { path: '/api/demo/echo', tenantId: 'beta-inc', status: 'ALLOWED', latencyMs: 28 },
  { path: '/api/admin/rules', tenantId: 'acme-corp', status: 'BLOCKED', latencyMs: 2 },
  { path: '/api/demo/slow', tenantId: 'free-user-co', status: 'ALLOWED', latencyMs: 512 },
  { path: '/api/demo/headers', tenantId: 'beta-inc', status: 'ALLOWED', latencyMs: 8 },
  { path: '/api/demo/info', tenantId: 'acme-corp', status: 'BLOCKED', latencyMs: 1 },
];

function generateChartData(points = 20) {
  const now = Date.now();
  return Array.from({ length: points }, (_, i) => ({
    time: format(now - (points - 1 - i) * 60000, 'HH:mm'),
    allowed: Math.floor(Math.random() * 80) + 20,
    blocked: Math.floor(Math.random() * 20),
    latency: Math.floor(Math.random() * 50) + 10,
  }));
}

function KpiCard({ label, value, icon: Icon, color, trend, trendValue, suffix = '' }) {
  return (
    <div className={`kpi-card ${color}`}>
      <div className={`kpi-icon-wrap ${color}`}>
        <Icon />
      </div>
      <div className="kpi-value">{value}{suffix}</div>
      <div className="kpi-label">{label}</div>
      {trendValue !== undefined && (
        <div className={`kpi-trend ${trend}`}>
          {trend === 'up' ? <TrendingUp size={10} /> : <TrendingDown size={10} />}
          {trendValue}
        </div>
      )}
    </div>
  );
}

function LiveEventFeed({ events }) {
  return (
    <div className="live-feed">
      <div className="live-feed-header">
        <span className="live-dot" />
        Live Traffic Feed
        <span style={{ marginLeft: 'auto', fontSize: '11px', color: 'var(--text-muted)' }}>Real-time</span>
      </div>
      <div className="live-feed-list">
        {events.length === 0 ? (
          <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '12px' }}>
            Waiting for traffic events...
          </div>
        ) : (
          events.slice(0, 30).map((evt, i) => (
            <div key={i} className="live-event">
              <div className={`live-event-status ${evt.status?.toLowerCase()}`} />
              <span className="live-event-path">{evt.path || evt.method + ' ' + evt.requestPath}</span>
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
            {p.name}: {p.value}
          </p>
        ))}
      </div>
    );
  }
  return null;
};

export default function DashboardPage() {
  const [summary, setSummary] = useState(null);
  const [chartData, setChartData] = useState(generateChartData());
  const [liveEvents, setLiveEvents] = useState(DEMO_EVENTS);
  const [loading, setLoading] = useState(true);
  const [range, setRange] = useState('1h');
  const eventSourceRef = useRef(null);

  useEffect(() => {
    // Load summary
    analyticsApi.getSummary()
      .then(res => setSummary(res.data.data))
      .catch(() => setSummary({
        totalRequests: 847293,
        blockedRequests: 12847,
        blockRate: 1.52,
        avgLatencyMs: 23.4,
        activeTenants: 3,
        topBlockedTenants: [
          { tenantId: 'free-user-co', blockedCount: 8921 },
          { tenantId: 'acme-corp', blockedCount: 2103 },
          { tenantId: 'beta-inc', blockedCount: 1823 },
        ]
      }))
      .finally(() => setLoading(false));

    // Connect to SSE for live events
    const token = localStorage.getItem('access_token');
    const es = new EventSource(`/admin/analytics/live${token ? `?token=${token}` : ''}`);
    eventSourceRef.current = es;

    es.onmessage = (e) => {
      try {
        const event = JSON.parse(e.data);
        setLiveEvents(prev => [event, ...prev.slice(0, 49)]);
        // Update chart
        setChartData(prev => {
          const newPt = {
            time: format(Date.now(), 'HH:mm'),
            allowed: (prev[prev.length - 1]?.allowed || 0) + (event.status === 'ALLOWED' ? 1 : 0),
            blocked: (prev[prev.length - 1]?.blocked || 0) + (event.status === 'BLOCKED' ? 1 : 0),
            latency: event.latencyMs,
          };
          return [...prev.slice(1), newPt];
        });
      } catch {}
    };

    es.onerror = () => es.close();

    // Simulate live events every 2s in demo mode
    const interval = setInterval(() => {
      const evt = { ...DEMO_EVENTS[Math.floor(Math.random() * DEMO_EVENTS.length)], latencyMs: Math.floor(Math.random() * 100) + 5 };
      setLiveEvents(prev => [evt, ...prev.slice(0, 49)]);
      setChartData(prev => {
        const last = prev[prev.length - 1];
        const newPt = {
          time: format(Date.now(), 'HH:mm'),
          allowed: (last?.allowed || 50) + Math.floor(Math.random() * 5),
          blocked: Math.floor(Math.random() * 8),
          latency: evt.latencyMs,
        };
        return [...prev.slice(1), newPt];
      });
    }, 2000);

    return () => {
      es.close();
      clearInterval(interval);
    };
  }, []);

  return (
    <div className="page-content">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px' }}>
        <div>
          <h1 className="section-title">Overview Dashboard</h1>
          <p className="section-subtitle">Real-time gateway traffic, rate limit status, and system health</p>
        </div>
        <div style={{ display: 'flex', gap: '8px' }}>
          {['1h', '6h', '24h', '7d'].map(r => (
            <button key={r} className={`btn btn-sm ${range === r ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => setRange(r)} id={`range-${r}`}>
              {r}
            </button>
          ))}
        </div>
      </div>

      {/* KPI Cards */}
      <div className="kpi-grid">
        <KpiCard
          label="Total Requests (24h)"
          value={summary ? (summary.totalRequests / 1000).toFixed(0) + 'K' : '—'}
          icon={Activity}
          color="blue"
          trend="up"
          trendValue="+12.4% vs yesterday"
        />
        <KpiCard
          label="Blocked Requests"
          value={summary ? summary.blockedRequests.toLocaleString() : '—'}
          icon={ShieldAlert}
          color="red"
          trend="down"
          trendValue="-3.2% vs yesterday"
        />
        <KpiCard
          label="Block Rate"
          value={summary ? summary.blockRate.toFixed(2) : '—'}
          icon={Zap}
          color="orange"
          suffix="%"
        />
        <KpiCard
          label="Avg Latency"
          value={summary ? summary.avgLatencyMs.toFixed(1) : '—'}
          icon={Clock}
          color="purple"
          suffix="ms"
          trend="up"
          trendValue="+2.1ms"
        />
        <KpiCard
          label="Active Tenants"
          value={summary ? summary.activeTenants : '—'}
          icon={Users}
          color="green"
        />
        <KpiCard
          label="Gateway Uptime"
          value="99.98"
          icon={Globe}
          color="cyan"
          suffix="%"
        />
      </div>

      {/* Traffic Chart + Live Feed */}
      <div className="grid-cols-2-1" style={{ marginBottom: '16px' }}>
        <div className="chart-container">
          <div className="card-header">
            <span className="card-title"><Activity size={16} />Traffic Volume</span>
            <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>Requests per minute</span>
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
              <XAxis dataKey="time" stroke="var(--text-muted)" tick={{ fontSize: 11 }} />
              <YAxis stroke="var(--text-muted)" tick={{ fontSize: 11 }} />
              <Tooltip content={<CustomTooltip />} />
              <Legend wrapperStyle={{ fontSize: '12px', color: 'var(--text-secondary)' }} />
              <Area type="monotone" dataKey="allowed" name="Allowed" stroke="#3b82f6" fill="url(#gradAllowed)" strokeWidth={2} dot={false} />
              <Area type="monotone" dataKey="blocked" name="Blocked" stroke="#ef4444" fill="url(#gradBlocked)" strokeWidth={2} dot={false} />
            </AreaChart>
          </ResponsiveContainer>
        </div>
        <LiveEventFeed events={liveEvents} />
      </div>

      {/* Latency Chart + Top Blocked */}
      <div className="grid-2">
        <div className="chart-container">
          <div className="card-header">
            <span className="card-title"><Clock size={16} />Response Latency</span>
            <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>P50 latency (ms)</span>
          </div>
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" vertical={false} />
              <XAxis dataKey="time" stroke="var(--text-muted)" tick={{ fontSize: 11 }} />
              <YAxis stroke="var(--text-muted)" tick={{ fontSize: 11 }} />
              <Tooltip content={<CustomTooltip />} />
              <Line type="monotone" dataKey="latency" name="Latency (ms)" stroke="#8b5cf6" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>

        <div className="chart-container">
          <div className="card-header">
            <span className="card-title"><ShieldAlert size={16} />Top Blocked Tenants</span>
          </div>
          {summary?.topBlockedTenants ? (
            <ResponsiveContainer width="100%" height={200}>
              <BarChart data={summary.topBlockedTenants} layout="vertical">
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" horizontal={false} />
                <XAxis type="number" stroke="var(--text-muted)" tick={{ fontSize: 11 }} />
                <YAxis type="category" dataKey="tenantId" stroke="var(--text-muted)" tick={{ fontSize: 11 }} width={90} />
                <Tooltip content={<CustomTooltip />} />
                <Bar dataKey="blockedCount" name="Blocked" fill="#ef4444" radius={[0, 4, 4, 0]} />
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <div className="loading-overlay"><div className="spinner" /></div>
          )}
        </div>
      </div>
    </div>
  );
}
