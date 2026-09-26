import { useState, useEffect } from 'react';
import { analyticsApi } from '../services/api';
import { BarChart2, Clock, TrendingUp, ShieldAlert } from 'lucide-react';
import {
  AreaChart, Area, BarChart, Bar, XAxis, YAxis,
  CartesianGrid, Tooltip, ResponsiveContainer, Legend, PieChart, Pie, Cell
} from 'recharts';
import { format } from 'date-fns';

const RANGES = ['1h', '6h', '24h', '7d'];
const COLORS_PIE = ['#3b82f6', '#8b5cf6', '#10b981', '#f59e0b'];

const DEMO_TIMESERIES = Array.from({ length: 24 }, (_, i) => ({
  time: `${String(i).padStart(2, '0')}:00`,
  allowed: Math.floor(Math.random() * 3000) + 500,
  blocked: Math.floor(Math.random() * 300),
  avgLatency: Math.floor(Math.random() * 60) + 10,
}));

const DEMO_PIE = [
  { name: 'acme-corp', value: 45 },
  { name: 'beta-inc', value: 35 },
  { name: 'free-user-co', value: 15 },
  { name: 'other', value: 5 },
];

const DEMO_EVENTS = Array.from({ length: 20 }, (_, i) => ({
  id: i + 1,
  tenantId: ['acme-corp', 'beta-inc', 'free-user-co'][i % 3],
  userId: `user-${(i % 5) + 1}`,
  ipAddress: `192.168.1.${(i % 50) + 10}`,
  path: ['/api/demo/ping', '/api/demo/echo', '/api/admin/rules', '/api/demo/info'][i % 4],
  method: 'GET',
  status: i % 7 === 0 ? 'BLOCKED' : 'ALLOWED',
  latencyMs: Math.floor(Math.random() * 100) + 5,
  httpStatus: i % 7 === 0 ? 429 : 200,
  timestamp: new Date(Date.now() - i * 45000).toISOString(),
}));

const CustomTooltip = ({ active, payload, label }) => {
  if (active && payload && payload.length) {
    return (
      <div style={{ background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 'var(--radius)', padding: '10px 14px', fontSize: '12px' }}>
        <p style={{ color: 'var(--text-muted)', marginBottom: '6px' }}>{label}</p>
        {payload.map((p, i) => (
          <p key={i} style={{ color: p.color, fontWeight: 600 }}>{p.name}: {p.value}</p>
        ))}
      </div>
    );
  }
  return null;
};

export default function AnalyticsPage() {
  const [range, setRange] = useState('24h');
  const [tenantFilter, setTenantFilter] = useState('');
  const [timeSeries, setTimeSeries] = useState(DEMO_TIMESERIES);
  const [events, setEvents] = useState(DEMO_EVENTS);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      analyticsApi.getTimeSeries(tenantFilter || undefined, range),
      analyticsApi.getRecentEvents(tenantFilter || undefined, 0, 20),
    ])
      .then(([tsRes, evtRes]) => {
        setTimeSeries(tsRes.data.data || DEMO_TIMESERIES);
        setEvents(evtRes.data.data?.content || DEMO_EVENTS);
      })
      .catch(() => {
        setTimeSeries(DEMO_TIMESERIES);
        setEvents(DEMO_EVENTS);
      })
      .finally(() => setLoading(false));
  }, [range, tenantFilter]);

  const totalAllowed = timeSeries.reduce((s, p) => s + (p.allowed || 0), 0);
  const totalBlocked = timeSeries.reduce((s, p) => s + (p.blocked || 0), 0);
  const blockRate = totalAllowed + totalBlocked > 0
    ? ((totalBlocked / (totalAllowed + totalBlocked)) * 100).toFixed(2)
    : '0.00';
  const avgLatency = timeSeries.length
    ? (timeSeries.reduce((s, p) => s + (p.avgLatency || 0), 0) / timeSeries.length).toFixed(1)
    : '0';

  return (
    <div className="page-content">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px' }}>
        <div>
          <h1 className="section-title">Deep Analytics</h1>
          <p className="section-subtitle">Traffic patterns, latency histograms, and blocked request analysis</p>
        </div>
        <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          <select value={tenantFilter} onChange={e => setTenantFilter(e.target.value)}
            id="analytics-tenant-filter" style={{ width: 'auto', padding: '7px 12px' }}>
            <option value="">All Tenants</option>
            <option value="acme-corp">acme-corp</option>
            <option value="beta-inc">beta-inc</option>
            <option value="free-user-co">free-user-co</option>
          </select>
          {RANGES.map(r => (
            <button key={r} className={`btn btn-sm ${range === r ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => setRange(r)} id={`analytics-range-${r}`}>{r}</button>
          ))}
        </div>
      </div>

      {/* Stat Row */}
      <div className="kpi-grid" style={{ marginBottom: '20px' }}>
        <div className="kpi-card blue">
          <div className="kpi-icon-wrap blue"><TrendingUp /></div>
          <div className="kpi-value">{totalAllowed.toLocaleString()}</div>
          <div className="kpi-label">Total Allowed</div>
        </div>
        <div className="kpi-card red">
          <div className="kpi-icon-wrap red"><ShieldAlert /></div>
          <div className="kpi-value">{totalBlocked.toLocaleString()}</div>
          <div className="kpi-label">Total Blocked</div>
        </div>
        <div className="kpi-card orange">
          <div className="kpi-icon-wrap orange"><BarChart2 /></div>
          <div className="kpi-value">{blockRate}<span style={{ fontSize: '16px' }}>%</span></div>
          <div className="kpi-label">Block Rate</div>
        </div>
        <div className="kpi-card purple">
          <div className="kpi-icon-wrap purple"><Clock /></div>
          <div className="kpi-value">{avgLatency}<span style={{ fontSize: '16px' }}>ms</span></div>
          <div className="kpi-label">Avg Latency</div>
        </div>
      </div>

      {/* Traffic Over Time */}
      <div className="chart-container" style={{ marginBottom: '16px' }}>
        <div className="card-header">
          <span className="card-title"><BarChart2 size={16} />Traffic Over Time ({range})</span>
        </div>
        <ResponsiveContainer width="100%" height={260}>
          <AreaChart data={timeSeries}>
            <defs>
              <linearGradient id="analGradAllowed" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.25} />
                <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
              </linearGradient>
              <linearGradient id="analGradBlocked" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="#ef4444" stopOpacity={0.25} />
                <stop offset="95%" stopColor="#ef4444" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" vertical={false} />
            <XAxis dataKey="time" stroke="var(--text-muted)" tick={{ fontSize: 11 }} />
            <YAxis stroke="var(--text-muted)" tick={{ fontSize: 11 }} />
            <Tooltip content={<CustomTooltip />} />
            <Legend wrapperStyle={{ fontSize: '12px', color: 'var(--text-secondary)' }} />
            <Area type="monotone" dataKey="allowed" name="Allowed" stroke="#3b82f6" fill="url(#analGradAllowed)" strokeWidth={2} dot={false} />
            <Area type="monotone" dataKey="blocked" name="Blocked" stroke="#ef4444" fill="url(#analGradBlocked)" strokeWidth={2} dot={false} />
          </AreaChart>
        </ResponsiveContainer>
      </div>

      <div className="grid-2" style={{ marginBottom: '16px' }}>
        {/* Latency Chart */}
        <div className="chart-container">
          <div className="card-header">
            <span className="card-title"><Clock size={16} />Avg Response Latency</span>
          </div>
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={timeSeries.slice(-12)}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" vertical={false} />
              <XAxis dataKey="time" stroke="var(--text-muted)" tick={{ fontSize: 10 }} />
              <YAxis stroke="var(--text-muted)" tick={{ fontSize: 11 }} unit="ms" />
              <Tooltip content={<CustomTooltip />} />
              <Bar dataKey="avgLatency" name="Latency (ms)" fill="#8b5cf6" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>

        {/* Traffic Share Pie */}
        <div className="chart-container">
          <div className="card-header">
            <span className="card-title"><BarChart2 size={16} />Traffic Share by Tenant</span>
          </div>
          <ResponsiveContainer width="100%" height={200}>
            <PieChart>
              <Pie data={DEMO_PIE} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={80} innerRadius={45} paddingAngle={3}>
                {DEMO_PIE.map((_, i) => <Cell key={i} fill={COLORS_PIE[i % COLORS_PIE.length]} />)}
              </Pie>
              <Tooltip content={<CustomTooltip />} />
              <Legend wrapperStyle={{ fontSize: '12px', color: 'var(--text-secondary)' }} />
            </PieChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* Recent Events Table */}
      <div className="table-container">
        <div className="table-toolbar">
          <span className="table-toolbar-title"><ShieldAlert size={16} />Recent Traffic Events ({events.length})</span>
        </div>
        <table>
          <thead>
            <tr>
              <th>Timestamp</th>
              <th>Tenant</th>
              <th>User ID</th>
              <th>IP</th>
              <th>Path</th>
              <th>Status</th>
              <th>HTTP</th>
              <th>Latency</th>
            </tr>
          </thead>
          <tbody>
            {events.map((evt, i) => (
              <tr key={i}>
                <td className="mono" style={{ fontSize: '11px' }}>
                  {evt.timestamp ? format(new Date(evt.timestamp), 'HH:mm:ss') : '—'}
                </td>
                <td><span className="badge blue">{evt.tenantId}</span></td>
                <td className="mono">{evt.userId || '—'}</td>
                <td className="mono">{evt.ipAddress}</td>
                <td className="mono text-primary" style={{ maxWidth: '180px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{evt.path}</td>
                <td>
                  <span className={`badge ${evt.status === 'BLOCKED' ? 'red' : 'green'}`}>
                    {evt.status}
                  </span>
                </td>
                <td>
                  <span className={`badge ${evt.httpStatus === 429 ? 'red' : 'green'}`}>
                    {evt.httpStatus}
                  </span>
                </td>
                <td className={evt.latencyMs > 100 ? 'text-primary' : ''}>{evt.latencyMs}ms</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
