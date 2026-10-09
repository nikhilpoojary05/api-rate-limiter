import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { LayoutDashboard, ShieldCheck, Building2, BarChart2, Shield, LogOut, X } from 'lucide-react';

const NAV = [
  { section: 'Monitoring', items: [
    { to: '/dashboard', label: 'Overview', icon: LayoutDashboard, id: 'nav-dashboard' },
    { to: '/analytics', label: 'Analytics', icon: BarChart2, id: 'nav-analytics' },
  ] },
  { section: 'Configuration', items: [
    { to: '/rules', label: 'Rate limit rules', icon: ShieldCheck, id: 'nav-rules' },
    { to: '/tenants', label: 'Tenants', icon: Building2, id: 'nav-tenants' },
  ] },
];

/** Fixed on desktop; on narrow screens a drawer opened from the header. */
export default function Sidebar({ open, onClose }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  return (
    <aside className={`sidebar ${open ? 'open' : ''}`} aria-label="Main navigation">
      <div className="sidebar-logo">
        <div className="sidebar-logo-icon"><Shield size={15} /></div>
        <div className="sidebar-logo-text" style={{ flex: 1 }}>
          <div className="sidebar-logo-title">RateLimiter</div>
          <div className="sidebar-logo-sub">Security Gateway</div>
        </div>
        <button className="icon-btn burger" onClick={onClose} aria-label="Close menu"><X size={16} /></button>
      </div>

      <nav className="sidebar-nav">
        {NAV.map(group => (
          <div key={group.section}>
            <div className="nav-section-label">{group.section}</div>
            {group.items.map(({ to, label, icon: Icon, id }) => (
              <NavLink key={to} to={to} id={id}
                className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
                <Icon size={16} />
                {label}
              </NavLink>
            ))}
          </div>
        ))}
      </nav>

      <div className="sidebar-footer">
        {user && (
          <div className="sidebar-user">
            <div className="avatar">{(user.username || '?')[0].toUpperCase()}</div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: '13px', fontWeight: 600 }}>{user.username}</div>
              <div style={{ fontSize: '11.5px', color: 'var(--text-muted)' }}>{user.tenantId}</div>
            </div>
          </div>
        )}
        <button className="nav-item" style={{ width: '100%', border: 0, background: 'none', cursor: 'pointer' }}
          id="logout-btn" onClick={handleLogout}>
          <LogOut size={16} />
          Sign out
        </button>
      </div>
    </aside>
  );
}
