import { useAuth } from '../context/AuthContext';
import { useTheme } from '../lib/theme';
import { Menu, Moon, Sun, ChevronRight } from 'lucide-react';

// Most privileged first: every account also holds ROLE_USER, and showing the first role
// in the list labelled the super-admin as a plain user.
const ROLE_RANK = ['ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_USER'];
const ROLE_NAMES = { ROLE_SUPER_ADMIN: 'Super admin', ROLE_ADMIN: 'Admin', ROLE_USER: 'User' };

function displayRole(roles = []) {
  const role = ROLE_RANK.find(r => roles.includes(r)) || roles[0];
  return ROLE_NAMES[role] || role || '';
}

export default function PageHeader({ page, onMenu }) {
  const { user } = useAuth();
  const [theme, toggleTheme] = useTheme();

  return (
    <header className="app-header">
      <div style={{ display: 'flex', alignItems: 'center', gap: '10px', minWidth: 0 }}>
        <button className="icon-btn burger" onClick={onMenu} aria-label="Open menu"><Menu size={16} /></button>
        <div className="breadcrumb">
          <span className="hide-sm">Gateway</span>
          <ChevronRight size={14} className="hide-sm" />
          <strong>{page}</strong>
        </div>
      </div>

      <div className="header-actions">
        <button className="icon-btn" onClick={toggleTheme} id="theme-toggle"
          aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
          title={theme === 'dark' ? 'Light theme' : 'Dark theme'}>
          {theme === 'dark' ? <Sun size={15} /> : <Moon size={15} />}
        </button>
        {user && (
          <div className="user-chip">
            <div className="avatar">{(user.username || '?')[0].toUpperCase()}</div>
            <div className="user-chip-text">
              <div className="user-chip-name">{user.username}</div>
              <div className="user-chip-role">{displayRole(user.roles)}</div>
            </div>
          </div>
        )}
      </div>
    </header>
  );
}
