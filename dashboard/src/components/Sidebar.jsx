import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import {
  LayoutDashboard, ShieldCheck, Building2, BarChart2,
  Shield, LogOut
} from 'lucide-react';

export default function Sidebar() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  return (
    <aside className="sidebar">
      <div className="sidebar-logo">
        <div className="sidebar-logo-icon">
          <Shield size={18} />
        </div>
        <div className="sidebar-logo-text">
          <div className="sidebar-logo-title">RateLimiter</div>
          <div className="sidebar-logo-sub">Security Gateway</div>
        </div>
      </div>

      <nav className="sidebar-nav">
        <div className="nav-section-label">Monitoring</div>

        <NavLink to="/dashboard" id="nav-dashboard"
          className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
          <LayoutDashboard size={16} />
          Dashboard
        </NavLink>

        <NavLink to="/analytics" id="nav-analytics"
          className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
          <BarChart2 size={16} />
          Analytics
        </NavLink>

        <div className="nav-section-label">Configuration</div>

        <NavLink to="/rules" id="nav-rules"
          className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
          <ShieldCheck size={16} />
          Rate Limit Rules
        </NavLink>

        <NavLink to="/tenants" id="nav-tenants"
          className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
          <Building2 size={16} />
          Tenants
        </NavLink>
      </nav>

      <div className="sidebar-footer">
        {user && (
          <div style={{ padding: '8px 10px', marginBottom: '8px' }}>
            <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)' }}>
              {user.username}
            </div>
            <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
              {user.tenantId}
            </div>
          </div>
        )}

        <button className="nav-item" style={{ width: '100%', border: 'none', cursor: 'pointer', background: 'none' }}
          id="logout-btn" onClick={handleLogout}>
          <LogOut size={16} />
          Sign Out
        </button>
      </div>
    </aside>
  );
}
