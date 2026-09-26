import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import Sidebar from './components/Sidebar';
import PageHeader from './components/PageHeader';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import RulesPage from './pages/RulesPage';
import TenantsPage from './pages/TenantsPage';
import AnalyticsPage from './pages/AnalyticsPage';

const PAGE_META = {
  '/dashboard': { title: 'Overview Dashboard', subtitle: 'Real-time gateway metrics and live traffic' },
  '/analytics': { title: 'Deep Analytics', subtitle: 'Traffic patterns, latency, and blocked requests' },
  '/rules': { title: 'Rate Limit Rules', subtitle: 'Manage per-tenant and per-tier policies' },
  '/tenants': { title: 'Tenant Management', subtitle: 'Organizations and rate limit tiers' },
};

function ProtectedLayout({ children }) {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100vh', background: 'var(--bg-primary)' }}>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '16px' }}>
          <div style={{
            width: '48px', height: '48px',
            background: 'linear-gradient(135deg, var(--accent-primary), var(--accent-secondary))',
            borderRadius: '12px', display: 'flex', alignItems: 'center', justifyContent: 'center'
          }}>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            </svg>
          </div>
          <div className="spinner" style={{ width: 24, height: 24 }} />
          <span style={{ color: 'var(--text-muted)', fontSize: '13px' }}>Loading...</span>
        </div>
      </div>
    );
  }

  if (!user) return <Navigate to="/login" replace />;

  const path = window.location.pathname;
  const meta = PAGE_META[path] || {};

  return (
    <div className="app-layout">
      <Sidebar />
      <main className="main-content">
        <PageHeader title={meta.title} subtitle={meta.subtitle} />
        {children}
      </main>
    </div>
  );
}

function AppRoutes() {
  const { user } = useAuth();

  return (
    <Routes>
      <Route path="/login" element={user ? <Navigate to="/dashboard" replace /> : <LoginPage />} />
      <Route path="/dashboard" element={<ProtectedLayout><DashboardPage /></ProtectedLayout>} />
      <Route path="/analytics" element={<ProtectedLayout><AnalyticsPage /></ProtectedLayout>} />
      <Route path="/rules" element={<ProtectedLayout><RulesPage /></ProtectedLayout>} />
      <Route path="/tenants" element={<ProtectedLayout><TenantsPage /></ProtectedLayout>} />
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </AuthProvider>
  );
}
