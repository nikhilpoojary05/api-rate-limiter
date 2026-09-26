import { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import { Shield, AlertCircle } from 'lucide-react';

export default function LoginPage() {
  const { login } = useAuth();
  const [form, setForm] = useState({ username: 'admin', password: 'Admin@123!', tenantId: 'acme-corp' });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await login(form);
      window.location.href = '/dashboard';
    } catch (err) {
      setError(err.response?.data?.message || 'Invalid credentials. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-logo">
          <div className="login-logo-icon">
            <Shield />
          </div>
          <div className="login-logo-text">
            <h1>RateLimiter</h1>
            <p>Security Gateway · Admin</p>
          </div>
        </div>

        <h2 style={{ fontSize: '20px', fontWeight: 700, marginBottom: '6px' }}>Welcome back</h2>
        <p style={{ fontSize: '13px', color: 'var(--text-muted)', marginBottom: '24px' }}>
          Sign in to manage rate limits and monitor traffic
        </p>

        {error && (
          <div className="login-error">
            <AlertCircle size={14} />
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="input-group">
            <label htmlFor="username">Username</label>
            <input
              id="username"
              type="text"
              placeholder="Enter your username"
              value={form.username}
              onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
              required
            />
          </div>

          <div className="input-group">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              placeholder="Enter your password"
              value={form.password}
              onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
              required
            />
          </div>

          <div className="input-group">
            <label htmlFor="tenantId">Tenant ID</label>
            <input
              id="tenantId"
              type="text"
              placeholder="e.g. acme-corp"
              value={form.tenantId}
              onChange={e => setForm(f => ({ ...f, tenantId: e.target.value }))}
            />
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            id="login-submit-btn"
            style={{ width: '100%', justifyContent: 'center', marginTop: '8px', padding: '11px 16px' }}
            disabled={loading}
          >
            {loading ? <><span className="spinner" style={{width:16,height:16}} /> Signing in...</> : 'Sign In'}
          </button>
        </form>

        <div style={{ marginTop: '20px', padding: '12px', background: 'var(--bg-elevated)', borderRadius: 'var(--radius)', border: '1px solid var(--border-subtle)' }}>
          <p style={{ fontSize: '11px', color: 'var(--text-muted)', fontWeight: 600, marginBottom: '4px', textTransform: 'uppercase', letterSpacing: '0.06em' }}>Demo Credentials</p>
          <p style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>admin / Admin@123! / acme-corp</p>
        </div>
      </div>
    </div>
  );
}
