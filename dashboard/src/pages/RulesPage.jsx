import { useState, useEffect } from 'react';
import { rulesApi, errorMessage } from '../services/api';
import { ShieldCheck, Plus, Pencil, Trash2, RefreshCw, X, AlertCircle } from 'lucide-react';

const TIERS = ['TIER_FREE', 'TIER_A', 'TIER_B', 'TIER_ENTERPRISE'];
const ALGORITHMS = ['SLIDING_WINDOW', 'TOKEN_BUCKET'];

const TIER_COLORS = {
  TIER_FREE: 'gray',
  TIER_A: 'blue',
  TIER_B: 'purple',
  TIER_ENTERPRISE: 'cyan',
};

function RuleModal({ rule, onClose, onSave }) {
  const [form, setForm] = useState(rule || {
    tenantId: '', tier: 'TIER_A', algorithm: 'SLIDING_WINDOW',
    requestLimit: 100, windowMs: 60000, burstCapacity: 150, active: true
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const set = (k, v) => setForm(f => ({ ...f, [k]: v }));

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      if (form.id) {
        await rulesApi.update(form.id, form);
      } else {
        await rulesApi.create(form);
      }
      onSave();
    } catch (err) {
      setError(errorMessage(err, 'Failed to save rule'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span className="modal-title">{form.id ? 'Edit Rate Limit Rule' : 'Create Rate Limit Rule'}</span>
          <button className="btn btn-secondary btn-icon" onClick={onClose} id="close-rule-modal"><X size={16} /></button>
        </div>

        {error && (
          <div style={{ background: 'var(--accent-danger-glow)', border: '1px solid rgba(239,68,68,0.3)', borderRadius: 'var(--radius)', padding: '10px 12px', color: 'var(--accent-danger)', fontSize: '13px', marginBottom: '16px', display: 'flex', gap: '8px', alignItems: 'center' }}>
            <AlertCircle size={14} />{error}
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="grid-2">
            <div className="input-group">
              <label>Tenant ID</label>
              <input value={form.tenantId} onChange={e => set('tenantId', e.target.value)}
                placeholder="e.g. acme-corp" required id="rule-tenant-id" />
            </div>
            <div className="input-group">
              <label>Tier</label>
              <select value={form.tier} onChange={e => set('tier', e.target.value)} id="rule-tier">
                {TIERS.map(t => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
          </div>

          <div className="input-group">
            <label>Algorithm</label>
            <select value={form.algorithm} onChange={e => set('algorithm', e.target.value)} id="rule-algorithm">
              {ALGORITHMS.map(a => <option key={a} value={a}>{a.replace('_', ' ')}</option>)}
            </select>
          </div>

          <div className="grid-2">
            <div className="input-group">
              <label>Request Limit</label>
              <input type="number" value={form.requestLimit} onChange={e => set('requestLimit', +e.target.value)}
                min="1" required id="rule-limit" />
            </div>
            <div className="input-group">
              <label>Window (ms)</label>
              <input type="number" value={form.windowMs} onChange={e => set('windowMs', +e.target.value)}
                min="1000" step="1000" required id="rule-window" />
            </div>
          </div>

          <div className="input-group">
            <label>Burst Capacity (Token Bucket only)</label>
            <input type="number" value={form.burstCapacity} onChange={e => set('burstCapacity', +e.target.value)}
              min="0" id="rule-burst" />
          </div>

          <div className="toggle-wrap" style={{ marginBottom: '8px' }}>
            <button type="button" id="rule-active-toggle"
              className={`toggle ${form.active ? 'on' : ''}`}
              onClick={() => set('active', !form.active)}>
              <div className="toggle-track" /><div className="toggle-thumb" />
            </button>
            <span style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
              {form.active ? 'Active' : 'Inactive'}
            </span>
          </div>

          <div className="modal-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose} id="cancel-rule-btn">Cancel</button>
            <button type="submit" className="btn btn-primary" id="save-rule-btn" disabled={loading}>
              {loading ? <><span className="spinner" style={{width:14,height:14}} />Saving...</> : <><ShieldCheck size={14} />{form.id ? 'Update Rule' : 'Create Rule'}</>}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function RulesPage() {
  const [rules, setRules] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editRule, setEditRule] = useState(null);
  const [filter, setFilter] = useState('');
  const [loadError, setLoadError] = useState('');

  const fetchRules = () => {
    setLoading(true);
    setLoadError('');
    rulesApi.getAll()
      .then(res => setRules(res.data.data || []))
      .catch(err => {
        setRules([]);
        setLoadError(errorMessage(err, 'Could not load rules'));
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchRules(); }, []);

  const handleDelete = async (id) => {
    if (!confirm('Delete this rule?')) return;
    try {
      await rulesApi.delete(id);
      fetchRules();
    } catch (err) {
      // Used to drop the rule from the table anyway, showing a delete that never happened.
      alert(errorMessage(err, 'Failed to delete rule'));
    }
  };

  const handlePublish = async () => {
    try {
      await rulesApi.publish();
      alert('Rules published to Redis gateway cache successfully.');
    } catch (err) {
      alert(errorMessage(err, 'Failed to publish rules. Check if admin-service is running.'));
    }
  };

  const filtered = rules.filter(r =>
    !filter || r.tenantId.includes(filter) || r.tier.includes(filter)
  );

  return (
    <div className="page-content">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px' }}>
        <div>
          <h1 className="section-title">Rate Limit Rules</h1>
          <p className="section-subtitle">Manage per-tenant, per-tier rate limiting policies</p>
        </div>
        <div style={{ display: 'flex', gap: '8px' }}>
          <button className="btn btn-secondary" onClick={handlePublish} id="publish-rules-btn">
            <RefreshCw size={14} />Publish to Gateway
          </button>
          <button className="btn btn-primary" onClick={() => { setEditRule(null); setShowModal(true); }} id="create-rule-btn">
            <Plus size={14} />New Rule
          </button>
        </div>
      </div>

      <div className="table-container">
        <div className="table-toolbar">
          <span className="table-toolbar-title"><ShieldCheck size={16} />Rules ({filtered.length})</span>
          <div className="search-input-wrap">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}><circle cx={11} cy={11} r={8}/><path d="m21 21-4.35-4.35"/></svg>
            <input id="rules-search" type="text" placeholder="Filter by tenant or tier..." value={filter} onChange={e => setFilter(e.target.value)} />
          </div>
        </div>

        {loading ? (
          <div className="loading-overlay"><div className="spinner" /><span>Loading rules...</span></div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Tenant ID</th>
                <th>Tier</th>
                <th>Algorithm</th>
                <th>Limit</th>
                <th>Window</th>
                <th>Burst</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {loadError ? (
                <tr><td colSpan={8}>
                  <div className="empty-state">
                    <AlertCircle />
                    <h3>Could not load rules</h3>
                    <p>{loadError}</p>
                  </div>
                </td></tr>
              ) : filtered.length === 0 ? (
                <tr><td colSpan={8}>
                  <div className="empty-state">
                    <ShieldCheck />
                    <h3>No rules found</h3>
                    <p>Create your first rate limit rule to get started</p>
                  </div>
                </td></tr>
              ) : (
                filtered.map(rule => (
                  <tr key={rule.id}>
                    <td className="text-primary mono">{rule.tenantId}</td>
                    <td>
                      <span className={`badge ${TIER_COLORS[rule.tier] || 'gray'}`}>{rule.tier}</span>
                    </td>
                    <td>
                      <span className={`algo-badge ${rule.algorithm === 'SLIDING_WINDOW' ? 'sliding' : 'token'}`}>
                        {rule.algorithm === 'SLIDING_WINDOW' ? '⊘ Sliding Window' : '◉ Token Bucket'}
                      </span>
                    </td>
                    <td className="text-primary">{rule.requestLimit.toLocaleString()} req</td>
                    <td>{(rule.windowMs / 1000).toFixed(0)}s</td>
                    <td>{rule.burstCapacity}</td>
                    <td>
                      <span className={`badge ${rule.active ? 'green' : 'gray'}`}>
                        {rule.active ? '● Active' : '○ Inactive'}
                      </span>
                    </td>
                    <td>
                      <div style={{ display: 'flex', gap: '6px' }}>
                        <button className="btn btn-secondary btn-sm btn-icon"
                          id={`edit-rule-${rule.id}`}
                          onClick={() => { setEditRule(rule); setShowModal(true); }}>
                          <Pencil size={12} />
                        </button>
                        <button className="btn btn-danger btn-sm btn-icon"
                          id={`delete-rule-${rule.id}`}
                          onClick={() => handleDelete(rule.id)}>
                          <Trash2 size={12} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        )}
      </div>

      {showModal && (
        <RuleModal
          rule={editRule}
          onClose={() => { setShowModal(false); setEditRule(null); }}
          onSave={() => { setShowModal(false); setEditRule(null); fetchRules(); }}
        />
      )}
    </div>
  );
}
