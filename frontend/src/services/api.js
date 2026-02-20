import axios from "axios";
import { session } from "./session";

/**
 * Single HTTP client for the GitForge API.
 *
 * Replaces the hardcoded per-component URLs the previous frontend used, and is
 * the only place the auth header is attached.
 */
export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1",
  headers: { "Content-Type": "application/json" },
});

api.interceptors.request.use((config) => {
  const token = session.getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    // A 401 while holding a token means the session lapsed. A 401 without one is
    // an ordinary failed sign-in, which the form reports itself.
    if (error.response?.status === 401 && session.getToken()) {
      session.clear();
      if (!isAuthRoute(window.location.pathname)) {
        window.location.assign("/login");
      }
    }
    return Promise.reject(error);
  },
);

function isAuthRoute(pathname) {
  return pathname === "/login" || pathname === "/signup";
}

/**
 * Extracts a displayable message from a failed request, preferring the server's
 * ApiError body and falling back to validation details or a generic message.
 */
export function errorMessage(error, fallback = "Something went wrong") {
  const data = error?.response?.data;
  if (!data) {
    return error?.message === "Network Error"
      ? "Cannot reach the server. Is it running?"
      : fallback;
  }
  if (data.fieldErrors?.length) {
    return data.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(", ");
  }
  return data.message ?? fallback;
}
