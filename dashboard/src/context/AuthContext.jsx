import { createContext, useContext, useState, useEffect } from 'react';
import { authApi } from '../services/api';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const token = localStorage.getItem('access_token');
    if (token) {
      authApi.me()
        .then(res => setUser(res.data.data))
        .catch(() => { localStorage.clear(); })
        .finally(() => setLoading(false));
    } else {
      setLoading(false);
    }
  }, []);

  const login = async (credentials) => {
    const res = await authApi.login(credentials);
    // The refresh token is deliberately not stored: it arrives as an HttpOnly cookie
    // that this code cannot read, so an XSS cannot exfiltrate it.
    const { accessToken, user: userInfo } = res.data.data;
    localStorage.setItem('access_token', accessToken);
    setUser(userInfo);
    return userInfo;
  };

  const logout = async () => {
    try { await authApi.logout(); } catch { /* clear local state regardless */ }
    localStorage.clear();
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ user, login, logout, loading }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
