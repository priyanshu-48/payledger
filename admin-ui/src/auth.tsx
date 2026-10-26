import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { api, json, setToken, setUnauthorizedHandler } from "./api";

export interface Session {
  username: string;
  role: string;
}

interface AuthState {
  session: Session | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

// The role is read from the token for display and to hide menu entries only; the services decide what is allowed.
function readSession(accessToken: string): Session {
  const payload = JSON.parse(atob(accessToken.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
  return { username: payload.sub, role: payload.roles?.[0] ?? "" };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);

  const logout = () => {
    setToken(null);
    setSession(null);
  };
  useEffect(() => setUnauthorizedHandler(logout), []);

  const login = async (username: string, password: string) => {
    const { accessToken } = await api<{ accessToken: string }>("auth", "/auth/token", json({ username, password }));
    setToken(accessToken);
    setSession(readSession(accessToken));
  };

  return <AuthContext.Provider value={{ session, login, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth outside AuthProvider");
  return value;
}
