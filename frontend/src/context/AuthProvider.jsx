import { useCallback, useEffect, useMemo, useState } from "react";

import { AuthContext } from "./AuthContext";
import { authService } from "../services/authService";
import { session } from "../services/session";

export const AuthProvider = ({ children }) => {
  const [currentUser, setCurrentUser] = useState(() => session.getUser());
  // Distinguishes "not signed in" from "still checking", so routes do not
  // redirect to the login page during the initial token validation.
  const [loading, setLoading] = useState(() => Boolean(session.getToken()));

  useEffect(() => {
    if (!session.getToken()) {
      setLoading(false);
      return undefined;
    }

    let cancelled = false;

    // Confirm the stored token is still valid rather than trusting cached state.
    authService
      .me()
      .then((user) => {
        if (cancelled) return;
        session.save(session.getToken(), user);
        setCurrentUser(user);
      })
      .catch(() => {
        if (cancelled) return;
        session.clear();
        setCurrentUser(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (credentials) => {
    const user = await authService.login(credentials);
    setCurrentUser(user);
    return user;
  }, []);

  const signup = useCallback(async (details) => {
    const user = await authService.signup(details);
    setCurrentUser(user);
    return user;
  }, []);

  const logout = useCallback(() => {
    authService.logout();
    setCurrentUser(null);
  }, []);

  const value = useMemo(
    () => ({ currentUser, loading, login, signup, logout }),
    [currentUser, loading, login, signup, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};
