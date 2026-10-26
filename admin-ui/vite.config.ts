import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Same table as nginx.conf: the browser only ever talks to its own origin (ADR 0021).
const proxy = (target: string, prefix: string) => ({
  target,
  changeOrigin: true,
  rewrite: (path: string) => path.replace(new RegExp(`^${prefix}`), ""),
});

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api/auth": proxy("http://localhost:8084", "/api/auth"),
      "/api/payments": proxy("http://localhost:8081", "/api/payments"),
      "/api/ledger": proxy("http://localhost:8082", "/api/ledger"),
    },
  },
});
