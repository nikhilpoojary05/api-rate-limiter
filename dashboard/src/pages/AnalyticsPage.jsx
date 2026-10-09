import { useState, useEffect } from 'react';
import { analyticsApi, tenantsApi, errorMessage } from '../services/api';
import { RANGES, RANGE_KEYS, toChartPoints, totals } from '../lib/timeseries';
import { useChartColors } from '../lib/theme';
import { BarChart2, Clock, TrendingUp, ShieldAlert, AlertCircle } from 'lucide-react';
import {
  AreaChart, Area, BarChart, Bar, XAxis, YAxis,
  CartesianGrid, Tooltip, ResponsiveContainer, Legend, PieChart, Pie, Cell
} from 'recharts';
import { format } from 'date-fns';

// Every figure here comes from the admin API. The page used to show generated sample
// traffic (tens of thousands of requests, invented users and IPs) whenever a request
// failed, and always for the traffic-share chart.


const CustomTooltip = ({ active, payload, label }) => {
  if (active && payload && payload.length) {
    return (
      <div style={{ background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 'var(--radius)', padding: '10px 14px', fontSize: '12px' }}>
        <p style={{ color: 'var(--text-muted)', marginBottom: '6px' }}>{label ?? payload[0].name}</p>
        {payload.map((p, i) => (
          <p key={i} style={{ color: p.color || p.payload?.fill, fontWeight: 600 }}>{p.name}: {p.value ?? '—'}</p>
        ))}
      </div>
    );
  }
  return null;
};

export default function AnalyticsPage() {
  // Theme colours: green for allowed and red for blocked, as everywhere else.
  const colors = useChartColors();
  const pie = [colors.accent, colors.secondary, colors.cyan, colors.warning, colors.allowed, colors.blocked];
  const [range, setRange] = useState('24h');
  const [tenants, setTenants] = useState([]);
  const [tenantFilter, setTenantFilter] = useState('');
  const [series, setSeries] = useState([]);
  const [events, setEvents] = useState([]);
  const [share, setShare] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // The API returns only the tenants this account may see: all of them for a
  // super-admin, just their own for a tenant admin.
  useEffect(() => {
    tenantsApi.getAll()
      .then(res => setTenants(res.data))
      .catch(err => setError(errorMessage(err, 'Could not load tenants')));
  }, []);

  const crossTenant = tenants.length > 1 && !tenantFilter;

  useEffect(() => {
    const scope = tenantFilter || undefined;
    setLoading(true);
    Promise.all([
      analyticsApi.getTimeSeries(scope, range),
      analyticsApi.getRecentEvents(scope, 0, 20),
    ])
      .then(([tsRes, evtRes]) => {
        setSeries(tsRes.data);
        setEvents(evtRes.data.content);
        setError('');
      })
      .catch(err => {
        setSeries([]);
        setEvents([]);
        setError(errorMessage(err, 'Could not load analytics'));
      })
      .finally(() => setLoading(false));
  }, [range, tenantFilter]);

  // Traffic share needs each tenant's own total for the range.
  useEffect(() => {
    if (!crossTenant) {
      setShare([]);
      return;
    }
    Promise.all(tenants.map(t => analyticsApi.getTimeSeries(t.tenantId, range)
      .then(res => {
        const { allowed, blocked } = totals(res.data);
        return { name: t.tenantId, value: allowed + blocked };
      })))
      .then(rows => setShare(rows.filter(r => r.value > 0)))
      .catch(() => setShare([]));
  }, [crossTenant, tenants, range]);

  const chartData = toChartPoints(series, range);
  const t = totals(series);
  const bucket = RANGES[range].bucket;

  return (
    <div className="page-content">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px' }}>
        <div>
          <h1 className="section-title">Deep Analytics</h1>
          <p className="section-subtitle">Traffic, latency and blocked requests for the selected range</p>
        </div>
        <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          <select value={tenantFilter} onChange={e => setTenantFilter(e.target.value)}
            id="analytics-tenant-filter" style={{ width: 'auto', padding: '7px 12px' }}>
            {tenants.length !== 1 && <option value="">All Tenants</option>}
            {tenants.map(tn => <option key={tn.tenantId} value={tn.tenantId}>{tn.tenantId}</option>)}
          </select>
          {RANGE_KEYS.map(r => (
            <button key={r} className={`btn btn-sm ${range === r ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => setRange(r)} id={`analytics-range-${r}`}>{r}</button>
          ))}
        </div>
      </div>

      {error && (
        <div style={{ background: 'var(--accent-danger-glow)', border: '1px solid rgba(239,68,68,0.3)', borderRadius: 'var(--radius)', padding: '10px 12px', color: 'var(--accent-danger)', fontSize: '13px', marginBottom: '16px', display: 'flex', gap: '8px', alignItems: 'center' }}>
          <AlertCircle size={14} />{error}
        </div>
      )}

      {/* Stat Row */}
      <div className="kpi-grid" style={{ marginBottom: '20px' }}>
        <div className="kpi-card blue">
          <div className="kpi-icon-wrap blue"><TrendingUp /></div>
          <div className="kpi-value">{t.allowed.toLocaleString()}</div>
          <div className="kpi-label">Allowed ({range})</div>
        </div>
        <div className="kpi-card red">
          <div className="kpi-icon-wrap red"><ShieldAlert /></div>
          <div className="kpi-value">{t.blocked.toLocaleString()}</div>
          <div className="kpi-label">Blocked ({range})</div>
        </div>
        <div className="kpi-card orange">
          <div className="kpi-icon-wrap orange"><BarChart2 /></div>
          <div className="kpi-value">{t.blockRate.toFixed(2)}<span style={{ fontSize: '16px' }}>%</span></div>
          <div className="kpi-label">Block Rate ({range})</div>
        </div>
        <div className="kpi-card purple">
          <div className="kpi-icon-wrap purple"><Clock /></div>
          <div className="kpi-value">{t.avgLatency.toFixed(1)}<span style={{ fontSize: '16px' }}>ms</span></div>
          <div className="kpi-label">Avg Latency ({range})</div>
        </div>
      </div>

      {/* Traffic Over Time */}
      <div className="chart-container" style={{ marginBottom: '16px' }}>
        <div className="card-header">
          <span className="card-title"><BarChart2 size={16} />Traffic Over Time ({range})</span>
          <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>Requests per {bucket}</span>
        </div>
        <ResponsiveContainer width="100%" height={260}>
          <AreaChart data={chartData}>
            <defs>
              <linearGradient id="analGradAllowed" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor={colors.allowed} stopOpacity={0.25} />
                <stop offset="95%" stopColor={colors.allowed} stopOpacity={0} />
              </linearGradient>
              <linearGradient id="analGradBlocked" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor={colors.blocked} stopOpacity={0.25} />
                <stop offset="95%" stopColor={colors.blocked} stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke={colors.grid} vertical={false} />
            <XAxis dataKey="time" stroke={colors.axis} tick={{ fontSize: 11 }} minTickGap={24} />
            <YAxis stroke={colors.axis} tick={{ fontSize: 11 }} allowDecimals={false} />
            <Tooltip content={<CustomTooltip />} />
            <Legend wrapperStyle={{ fontSize: '12px', color: 'var(--text-secondary)' }} />
            <Area type="monotone" dataKey="allowed" name="Allowed" stroke={colors.allowed} fill="url(#analGradAllowed)" strokeWidth={2} dot={false} />
            <Area type="monotone" dataKey="blocked" name="Blocked" stroke={colors.blocked} fill="url(#analGradBlocked)" strokeWidth={2} dot={false} />
          </AreaChart>
        </ResponsiveContainer>
      </div>

      <div className="grid-2" style={{ marginBottom: '16px' }}>
        {/* Latency Chart */}
        <div className="chart-container">
          <div className="card-header">
            <span className="card-title"><Clock size={16} />Avg Response Latency</span>
            <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>Time to first byte (ms) per {bucket}</span>
          </div>
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" stroke={colors.grid} vertical={false} />
              <XAxis dataKey="time" stroke={colors.axis} tick={{ fontSize: 10 }} minTickGap={24} />
              <YAxis stroke={colors.axis} tick={{ fontSize: 11 }} unit="ms" />
              <Tooltip content={<CustomTooltip />} />
              <Bar dataKey="latency" name="Latency (ms)" fill={colors.accent} radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>

        {/* Traffic Share Pie */}
        <div className="chart-container">
          <div className="card-header">
            <span className="card-title"><BarChart2 size={16} />Traffic Share by Tenant ({range})</span>
          </div>
          {!crossTenant ? (
            <div className="empty-state">
              <p>Shown when viewing all tenants</p>
            </div>
          ) : share.length === 0 ? (
            <div className="empty-state">
              <p>No traffic in this range</p>
            </div>
          ) : (
            <ResponsiveContainer width="100%" height={200}>
              <PieChart>
                <Pie data={share} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={80} innerRadius={45} paddingAngle={3}>
                  {share.map((_, i) => <Cell key={i} fill={pie[i % pie.length]} />)}
                </Pie>
                <Tooltip content={<CustomTooltip />} />
                <Legend wrapperStyle={{ fontSize: '12px', color: 'var(--text-secondary)' }} />
              </PieChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>

      {/* Recent Events Table */}
      <div className="table-container">
        <div className="table-toolbar">
          <span className="table-toolbar-title"><ShieldAlert size={16} />Latest Traffic Events ({events.length})</span>
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
            {!loading && events.length === 0 ? (
              <tr><td colSpan={8}>
                <div className="empty-state"><p>No traffic events yet</p></div>
              </td></tr>
            ) : events.map((evt, i) => (
              <tr key={i}>
                <td className="mono" style={{ fontSize: '11px' }}>
                  {evt.timestamp ? format(new Date(evt.timestamp), 'dd MMM HH:mm:ss') : '—'}
                </td>
                <td><span className="badge blue">{evt.tenantId}</span></td>
                <td className="mono">{evt.userId || '—'}</td>
                <td className="mono">{evt.ipAddress}</td>
                <td className="mono text-primary" style={{ maxWidth: '180px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{evt.method} {evt.path}</td>
                <td>
                  <span className={`badge ${evt.status === 'BLOCKED' ? 'red' : evt.status === 'ERROR' ? 'orange' : 'green'}`}>
                    {evt.status}
                  </span>
                </td>
                <td>
                  <span className={`badge ${evt.httpStatus >= 400 ? 'red' : 'green'}`}>
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
