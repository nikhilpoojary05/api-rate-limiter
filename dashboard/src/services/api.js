import axios from 'axios';

// Everything goes through the gateway, which is what enforces authentication and
// rate limiting. Talking to auth-service and admin-service directly bypassed both.
const api = axios.create({
  baseURL: '/api',
  timeout: 10000,
  // Sends the HttpOnly refresh_token cookie.
  withCredentials: true,
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
      // The refresh token lives in an HttpOnly cookie, so it is not read here and is
      // never placed in a URL — it used to be a query parameter, which leaks it into
      // access logs, browser history and Referer headers.
      try {
        const res = await api.post('/auth/refresh', {});
        const newToken = res.data.data?.accessToken;
        if (newToken) {
          localStorage.setItem('access_token', newToken);
          originalRequest.headers.Authorization = `Bearer ${newToken}`;
          return api(originalRequest);
        }
        throw new Error('No access token in refresh response');
      } catch {
        localStorage.clear();
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

/**
 * The message from a failed request. The services answer errors as either
 * {message} or {error}, so both are read before falling back.
 */
export function errorMessage(err, fallback) {
  const body = err?.response?.data;
  return body?.message || body?.error || fallback;
}

// ─── Auth ─────────────────────────────────────────────────────────────────────
export const authApi = {
  login: (data) => api.post('/auth/login', data),
  logout: () => api.post('/auth/logout', {}),
  me: () => api.get('/auth/me'),
};

// ─── Analytics ────────────────────────────────────────────────────────────────
export const analyticsApi = {
  getSummary: (tenantId) =>
    api.get('/admin/analytics/summary', { params: { tenantId } }),
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
  // Returns the new key in full, this once; it cannot be read back afterwards.
  rotateApiKey: (id) => api.post(`/admin/tenants/${id}/api-key`),
};

export default api;
