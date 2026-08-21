import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
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
});
