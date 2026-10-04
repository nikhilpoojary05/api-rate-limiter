import { useAuth } from '../context/AuthContext';
import { Shield } from 'lucide-react';

// Most privileged first: every account also holds ROLE_USER, and showing the first role
// in the list labelled the super-admin as a plain user.
const ROLE_RANK = ['ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_USER'];

function displayRole(roles = []) {
  return ROLE_RANK.find(r => roles.includes(r)) || roles[0] || '';
}

export default function PageHeader({ title, subtitle, actions }) {
  const { user } = useAuth();

  return (
    <header className="page-header">
      <div className="page-header-left">
        <div className="page-header-title">{title}</div>
        {subtitle && <div className="page-header-sub">{subtitle}</div>}
      </div>
      <div className="page-header-right">
        {actions}
        {user && (
          <div style={{
            display: 'flex', alignItems: 'center', gap: '8px',
            padding: '6px 12px',
            background: 'var(--bg-elevated)',
            border: '1px solid var(--border-subtle)',
            borderRadius: 'var(--radius)',
          }}>
            <div style={{
              width: '28px', height: '28px',
              background: 'linear-gradient(135deg, var(--accent-primary), var(--accent-secondary))',
              borderRadius: '50%',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: '12px', fontWeight: 700, color: 'white'
            }}>
              {(user.username || 'A')[0].toUpperCase()}
            </div>
            <div>
              <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)' }}>
                {user.username || 'Admin'}
              </div>
              <div style={{ fontSize: '10px', color: 'var(--text-muted)' }}>
                {displayRole(user.roles)}
              </div>
            </div>
          </div>
        )}
      </div>
    </header>
  );
}
