import { api } from "./api";
import { session } from "./session";

export const authService = {
  async signup({ username, email, password }) {
    const { data } = await api.post("/auth/signup", { username, email, password });
    session.save(data.token, data.user);
    return data.user;
  },

  async login({ email, password }) {
    const { data } = await api.post("/auth/login", { email, password });
    session.save(data.token, data.user);
    return data.user;
  },

  async me() {
    const { data } = await api.get("/auth/me");
    return data;
  },

  logout() {
    session.clear();
  },
};
