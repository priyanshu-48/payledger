import { defineConfig } from "@playwright/test";

// Runs against the real stack (ops/ui/smoke.sh starts it). Uses the Microsoft Edge that Windows ships, so no
// browser download is needed; set BROWSER_CHANNEL=chrome (or "" for Playwright's own Chromium) elsewhere.
export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: process.env.UI_URL ?? "http://localhost:8085",
    channel: process.env.BROWSER_CHANNEL ?? "msedge",
    viewport: { width: 1280, height: 800 },
  },
});
