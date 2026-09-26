import { useState, useEffect } from 'react';
import { tenantsApi } from '../services/api';
import { Building2, Plus, Pencil, X, AlertCircle, CheckCircle } from 'lucide-react';

const TIERS = ['TIER_FREE', 'TIER_A', 'TIER_B', 'TIER_ENTERPRISE'];
const TIER_COLORS = { TIER_FREE: 'gray', TIER_A: 'blue', TIER_B: 'purple', TIER_ENTERPRISE: 'cyan' };

const DEMO_TENANTS = [
  { id: 'acme-corp', name: 'Acme Corporation', tier: 'TIER_A', description: 'Enterprise API client - Tier A rate limits', active: true, createdAt: '2024-01-15T10:00:00Z' },
  { id: 'beta-inc', name: 'Beta Inc', tier: 'TIER_B', description: 'Premium partner integration', active: true, createdAt: '2024-02-20T09:30:00Z' },
  { id: 'free-user-co', name: 'Free User Co', tier: 'TIER_FREE', description: 'Free tier trial account', active: true, createdAt: '2024-03-01T14:00:00Z' },
];

function TenantModal({ tenant, onClose, onSave }) {
  const [form, setForm] = useState(tenant || { id: '', name: '', tier: 'TIER_FREE', description: '', active: true });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }));

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      if (form.id && tenant) {
        await tenantsApi.update(form.id, form);
      } else {
        await tenantsApi.create(form);
      }
      onSave();
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to save tenant');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span className="modal-title">{tenant ? 'Edit Tenant' : 'Create Tenant'}</span>
          <button className="btn btn-secondary btn-icon" onClick={onClose} id="close-tenant-modal"><X size={16} /></button>
        </div>

        {error && (
          <div style={{ background: 'var(--accent-danger-glow)', border: '1px solid rgba(239,68,68,0.3)', borderRadius: 'var(--radius)', padding: '10px 12px', color: 'var(--accent-danger)', fontSize: '13px', marginBottom: '16px', display: 'flex', gap: '8px', alignItems: 'center' }}>
            <AlertCircle size={14} />{error}
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="input-group">
            <label>Tenant ID (slug)</label>
            <input value={form.id} onChange={e => set('id', e.target.value.toLowerCase().replace(/\s+/g, '-'))}
              placeholder="e.g. my-company" required disabled={!!tenant} id="tenant-id-input" />
          </div>
          <div className="input-group">
            <label>Display Name</label>
            <input value={form.name} onChange={e => set('name', e.target.value)}
              placeholder="e.g. My Company Ltd." required id="tenant-name-input" />
          </div>
          <div className="input-group">
            <label>Tier</label>
            <select value={form.tier} onChange={e => set('tier', e.target.value)} id="tenant-tier-select">
              {TIERS.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <div className="input-group">
            <label>Description</label>
            <textarea value={form.description} onChange={e => set('description', e.target.value)}
              placeholder="Optional description" rows={3} id="tenant-description"
              style={{ resize: 'vertical', minHeight: '80px' }} />
          </div>
          <div className="toggle-wrap" style={{ marginBottom: '8px' }}>
            <button type="button" id="tenant-active-toggle"
              className={`toggle ${form.active ? 'on' : ''}`}
              onClick={() => set('active', !form.active)}>
              <div className="toggle-track" /><div className="toggle-thumb" />
            </button>
            <span style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
              {form.active ? 'Active' : 'Inactive'}
            </span>
          </div>
          <div className="modal-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose} id="cancel-tenant-btn">Cancel</button>
            <button type="submit" className="btn btn-primary" id="save-tenant-btn" disabled={loading}>
              {loading ? <><span className="spinner" style={{width:14,height:14}} />Saving...</> : <><Building2 size={14} />{tenant ? 'Update' : 'Create'} Tenant</>}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function TenantsPage() {
  const [tenants, setTenants] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editTenant, setEditTenant] = useState(null);

  const fetchTenants = () => {
    setLoading(true);
    tenantsApi.getAll()
      .then(res => setTenants(res.data.data || []))
      .catch(() => setTenants(DEMO_TENANTS))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchTenants(); }, []);

  return (
    <div className="page-content">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px' }}>
        <div>
          <h1 className="section-title">Tenant Management</h1>
          <p className="section-subtitle">Configure organizations and their rate limit tiers</p>
        </div>
        <button className="btn btn-primary" onClick={() => { setEditTenant(null); setShowModal(true); }} id="create-tenant-btn">
          <Plus size={14} />New Tenant
        </button>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: '16px' }}>
        {loading ? (
          <div className="loading-overlay" style={{ gridColumn: '1/-1' }}><div className="spinner" /><span>Loading tenants...</span></div>
        ) : (
          tenants.map(tenant => (
            <div key={tenant.id} className="kpi-card" style={{ cursor: 'default' }}>
              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '12px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                  <div style={{
                    width: '44px', height: '44px',
                    background: 'linear-gradient(135deg, var(--accent-primary), var(--accent-secondary))',
                    borderRadius: 'var(--radius)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontSize: '18px', fontWeight: 700, color: 'white'
                  }}>
                    {tenant.name?.[0]?.toUpperCase() || 'T'}
                  </div>
                  <div>
                    <div style={{ fontWeight: 700, fontSize: '14px', color: 'var(--text-primary)' }}>{tenant.name}</div>
                    <div style={{ fontSize: '12px', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>{tenant.id}</div>
                  </div>
                </div>
                <div style={{ display: 'flex', gap: '6px' }}>
                  <button className="btn btn-secondary btn-sm btn-icon"
                    id={`edit-tenant-${tenant.id}`}
                    onClick={() => { setEditTenant(tenant); setShowModal(true); }}>
                    <Pencil size={12} />
                  </button>
                </div>
              </div>

              <div style={{ display: 'flex', gap: '8px', marginBottom: '12px', flexWrap: 'wrap' }}>
                <span className={`badge ${TIER_COLORS[tenant.tier] || 'gray'}`}>{tenant.tier}</span>
                <span className={`badge ${tenant.active ? 'green' : 'gray'}`}>
                  {tenant.active ? <><CheckCircle size={10} /> Active</> : '○ Inactive'}
                </span>
              </div>

              {tenant.description && (
                <p style={{ fontSize: '12px', color: 'var(--text-muted)', lineHeight: 1.5 }}>{tenant.description}</p>
              )}

              <div style={{ marginTop: '12px', paddingTop: '12px', borderTop: '1px solid var(--border-subtle)', fontSize: '11px', color: 'var(--text-muted)', display: 'flex', gap: '16px' }}>
                <span>Created {tenant.createdAt ? new Date(tenant.createdAt).toLocaleDateString() : '—'}</span>
              </div>
            </div>
          ))
        )}

        {!loading && tenants.length === 0 && (
          <div className="empty-state" style={{ gridColumn: '1/-1' }}>
            <Building2 />
            <h3>No tenants configured</h3>
            <p>Create your first tenant to start managing rate limits</p>
          </div>
        )}
      </div>

      {showModal && (
        <TenantModal
          tenant={editTenant}
          onClose={() => { setShowModal(false); setEditTenant(null); }}
          onSave={() => { setShowModal(false); setEditTenant(null); fetchTenants(); }}
        />
      )}
    </div>
  );
}
