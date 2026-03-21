import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

/**
 * The origin the browser is allowed to call.
 *
 * Derived from the configured API base rather than hardcoded, because that base
 * is a build-time setting - a policy naming localhost would silently block every
 * request once the app is deployed anywhere else.
 */
const apiOrigin = (base) => {
  try {
    return new URL(base).origin;
  } catch {
    return "";
  }
};

/**
 * Content-Security-Policy for the built application.
 *
 * Build only. The dev server serves its own client and websocket, and a policy
 * strict enough to be worth having in production breaks them - so it is applied
 * where it protects real visitors rather than weakened until it fits both.
 *
 * `style-src` must allow inline styles: Primer is built on styled-components,
 * which writes a <style> element at runtime, and without this the application
 * renders unstyled. Removing that would mean threading a nonce through
 * styled-components, which is a redesign rather than a header.
 *
 * `img-src data:` is required by the generated avatars, which are SVG data URIs.
 *
 * `script-src` deliberately has no 'unsafe-inline'. The production build emits
 * one external module script and no inline script, so it does not need it.
 *
 * `frame-ancestors` is listed but has no effect in a meta tag - only a real
 * header can carry it. The API already sends X-Frame-Options: DENY, and a
 * deployment should send this same policy as a header from whatever serves the
 * document.
 */
const contentSecurityPolicy = (connect) =>
  [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    `connect-src 'self'${connect ? " " + connect : ""}`,
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join("; ");

const cspPlugin = (env) => ({
  name: "gitforge-csp",
  apply: "build",
  transformIndexHtml(html) {
    const policy = contentSecurityPolicy(apiOrigin(env.VITE_API_BASE_URL ?? ""));
    return html.replace(
      "<head>",
      `<head>
    <meta http-equiv="Content-Security-Policy" content="${policy}" />`,
    );
  },
});

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");

  return {
    plugins: [react(), cspPlugin(env)],
    build: {
      rollupOptions: {
        output: {
          /**
           * Keeps the libraries in their own chunks.
           *
           * They change when a dependency is upgraded, which is rare, while
           * application code changes constantly. Splitting them means a returning
           * visitor re-downloads only what actually changed instead of the whole
           * bundle. Primer is separated from React because it is much the larger
           * of the two and moves on its own schedule.
           */
          manualChunks: {
            react: ["react", "react-dom", "react-router-dom"],
            primer: ["@primer/react", "@primer/octicons-react"],
          },
        },
      },
    },
    server: {
      port: 5173,
    },
    test: {
      environment: "jsdom",
      globals: true,
    },
  };
});
