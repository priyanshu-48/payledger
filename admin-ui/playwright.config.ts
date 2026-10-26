import { defineConfig } from "@playwright/test";

// Runs against the real stack (ops/ui/smoke.sh starts it). On Windows it uses the Microsoft Edge that ships with
// the OS, so no browser download is needed; elsewhere (Jenkins) Playwright's own Chromium. BROWSER_CHANNEL overrides.
export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: process.env.UI_URL ?? "http://localhost:8085",
    channel: process.env.BROWSER_CHANNEL || (process.platform === "win32" ? "msedge" : undefined),
    viewport: { width: 1280, height: 800 },
  },
});
