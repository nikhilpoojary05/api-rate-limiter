import axios from 'axios';

const api = axios.create({
  baseURL: '',
  timeout: 10000,
});

// Request interceptor: inject JWT
api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('access_token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// Response interceptor: handle 401
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;
    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true;
      const refreshToken = localStorage.getItem('refresh_token');
      if (refreshToken) {
        try {
          const res = await axios.post(`/auth/refresh?refreshToken=${refreshToken}`);
          const newToken = res.data.data?.accessToken;
          if (newToken) {
            localStorage.setItem('access_token', newToken);
            originalRequest.headers.Authorization = `Bearer ${newToken}`;
            return api(originalRequest);
          }
        } catch {
          localStorage.clear();
          window.location.href = '/login';
        }
      } else {
        localStorage.clear();
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

// ─── Auth ─────────────────────────────────────────────────────────────────────
export const authApi = {
  login: (data) => api.post('/auth/login', data),
  logout: (refreshToken) => api.post(`/auth/logout?refreshToken=${refreshToken}`),
  me: () => api.get('/auth/me'),
};

// ─── Analytics ────────────────────────────────────────────────────────────────
export const analyticsApi = {
  getSummary: () => api.get('/admin/analytics/summary'),
  getTimeSeries: (tenantId, range) =>
    api.get('/admin/analytics/timeseries', { params: { tenantId, range } }),
  getRecentEvents: (tenantId, page = 0, size = 50) =>
    api.get('/admin/analytics/events', { params: { tenantId, page, size } }),
};

// ─── Rules ────────────────────────────────────────────────────────────────────
export const rulesApi = {
  getAll: () => api.get('/admin/rules'),
  getByTenant: (tenantId) => api.get(`/admin/rules/${tenantId}`),
  create: (rule) => api.post('/admin/rules', rule),
  update: (id, rule) => api.put(`/admin/rules/${id}`, rule),
  delete: (id) => api.delete(`/admin/rules/${id}`),
  publish: () => api.post('/admin/rules/publish'),
};

// ─── Tenants ──────────────────────────────────────────────────────────────────
export const tenantsApi = {
  getAll: () => api.get('/admin/tenants'),
  getById: (id) => api.get(`/admin/tenants/${id}`),
  create: (tenant) => api.post('/admin/tenants', tenant),
  update: (id, tenant) => api.put(`/admin/tenants/${id}`, tenant),
};

export default api;
