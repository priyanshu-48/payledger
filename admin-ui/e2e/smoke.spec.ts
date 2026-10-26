import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { randomBytes } from "node:crypto";

// The whole admin console against the real stack. Data is seeded through the API (as a script would), then the
// browser does what an operator does. Needs AUTH_ADMIN_PASSWORD (the bootstrap admin) in the environment.
const adminPassword = process.env.AUTH_ADMIN_PASSWORD ?? "";
const run = Date.now().toString(36);
const merchant = `ui-merchant-${run}`;
const wallet = `ui-wallet-${run}`;
// A second funded wallet that no payment touches: the payer's balance changes when the ledger consumer posts a capture.
const saver = `ui-saver-${run}`;
const merchantPassword = randomBytes(12).toString("hex");

async function token(request: APIRequestContext, username: string, password: string): Promise<string> {
  const response = await request.post("/api/auth/auth/token", { data: { username, password } });
  expect(response.status(), `login ${username}`).toBe(200);
  return (await response.json()).accessToken;
}

const bearer = (t: string, extra: Record<string, string> = {}) => ({ Authorization: `Bearer ${t}`, ...extra });

async function signIn(page: Page, username: string, password: string) {
  await page.goto("/");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
}

let capturedId = "";
let openId = "";

test.beforeAll(async ({ request }) => {
  expect(adminPassword, "AUTH_ADMIN_PASSWORD must be set").not.toBe("");
  const admin = await token(request, "admin", adminPassword);
  const user = await request.post("/api/auth/auth/users", {
    headers: bearer(admin), data: { username: merchant, password: merchantPassword, role: "MERCHANT" },
  });
  expect(user.status()).toBe(201);

  const account = async (ownerId: string, type: string): Promise<string> => {
    const r = await request.post("/api/ledger/accounts", { headers: bearer(admin), data: { ownerId, type, currency: "INR" } });
    expect(r.status()).toBe(201);
    return (await r.json()).id;
  };
  const settlement = await account(`ui-settlement-${run}`, "SETTLEMENT");
  const walletId = await account(wallet, "USER_WALLET");
  await account(merchant, "MERCHANT");
  const saverId = await account(saver, "USER_WALLET");
  const saving = await request.post("/api/ledger/transfers", {
    headers: bearer(admin, { "Idempotency-Key": `ui-save-${run}` }),
    data: { fromAccountId: settlement, toAccountId: saverId, amount: 100.0, currency: "INR" },
  });
  expect(saving.status()).toBe(201);
  const funding = await request.post("/api/ledger/transfers", {
    headers: bearer(admin, { "Idempotency-Key": `ui-fund-${run}` }),
    data: { fromAccountId: settlement, toAccountId: walletId, amount: 100.0, currency: "INR" },
  });
  expect(funding.status()).toBe(201);

  const merchantToken = await token(request, merchant, merchantPassword);
  const pay = async (key: string, amount: number): Promise<string> => {
    const r = await request.post("/api/payments/payments", {
      headers: bearer(merchantToken, { "Idempotency-Key": `${key}-${run}` }),
      data: { merchantId: merchant, payerAccountId: walletId, amount, currency: "INR" },
    });
    expect(r.status()).toBe(201);
    return (await r.json()).id;
  };
  capturedId = await pay("ui-pay-1", 10.0);
  openId = await pay("ui-pay-2", 20.0);
  const capture = await request.post(`/api/payments/payments/${capturedId}/capture`, { headers: bearer(merchantToken) });
  expect(capture.status()).toBe(200);
});

test("a wrong password is refused and says so", async ({ page }) => {
  await signIn(page, "admin", "not-the-password");
  await expect(page.getByRole("alert")).toContainText("Not signed in");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
});

test("an admin works through accounts, payments, reconciliation and replay", async ({ page }) => {
  page.on("dialog", (d) => d.accept());
  await signIn(page, "admin", adminPassword);

  // Accounts: balances, filter, freeze and unfreeze
  await expect(page.getByRole("heading", { name: "Accounts" })).toBeVisible();
  await page.getByLabel("Owner").fill(saver);
  const saverRow = page.getByRole("row").filter({ hasText: saver });
  await expect(saverRow).toContainText("100.00 INR");
  await saverRow.getByRole("button", { name: "Freeze" }).click();
  await expect(saverRow).toContainText("FROZEN");
  await saverRow.getByRole("button", { name: "Unfreeze" }).click();
  await expect(saverRow).toContainText("ACTIVE");

  // Payments: history of a captured payment, then capture the open one from the UI
  await page.getByRole("link", { name: "Payments" }).click();
  await page.getByLabel("Status").selectOption("CAPTURED");
  await page.getByRole("row").filter({ hasText: merchant }).filter({ hasText: "CAPTURED" }).getByRole("link", { name: "Details" }).click();
  await expect(page.getByText(capturedId)).toBeVisible();
  const history = page.getByRole("list", { name: "Status history" }).getByRole("listitem");
  await expect(history).toHaveCount(3);
  await expect(history.nth(0)).toContainText("CREATED");
  await expect(history.nth(1)).toContainText("AUTHORIZED");
  await expect(history.nth(2)).toContainText("CAPTURED");
  await page.screenshot({ path: "../docs/img/admin-ui.png" });

  await page.getByRole("link", { name: "All payments" }).click();
  await expect(page.getByRole("heading", { name: "Payments" })).toBeVisible();
  await page.getByLabel("Status").selectOption("AUTHORIZED");
  await page.getByRole("row").filter({ hasText: merchant }).filter({ hasText: "AUTHORIZED" }).getByRole("link", { name: "Details" }).click();
  await expect(page.getByText(openId)).toBeVisible();
  await page.getByRole("button", { name: "Capture" }).click();
  await expect(page.getByRole("listitem").filter({ hasText: "from AUTHORIZED" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Capture" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Refund" })).toBeVisible();

  // Reconciliation: a file with a reference the ledger never captured
  await page.getByRole("link", { name: "Reconciliation" }).click();
  const ghost = `payment-capture:ghost-${run}`;
  const csv = `external_ref,amount_minor,currency,settled_at\n${ghost},500,INR,2026-10-26T10:00:00Z\n`;
  await page.locator('input[type="file"]').setInputFiles({ name: "settlement.csv", mimeType: "text/csv", buffer: Buffer.from(csv) });
  await page.locator('input[name="start"]').fill("2026-01-01T00:00");
  await page.locator('input[name="end"]').fill("2099-01-01T00:00");
  await page.getByRole("button", { name: "Reconcile" }).click();
  const mismatches = page.getByRole("table", { name: "Mismatches" });
  await expect(mismatches).toContainText(ghost);
  await expect(mismatches).toContainText("MISSING_IN_LEDGER");

  // Operations: dead-letter replay on a healthy stack replays nothing
  await page.getByRole("link", { name: "Operations" }).click();
  await page.getByRole("button", { name: "Replay dead letters" }).click();
  await expect(page.getByRole("status")).toContainText(/Replayed \d+ messages?/);
});

test("a merchant sees only payments, only its own, and a reload signs out", async ({ page }) => {
  await signIn(page, merchant, merchantPassword);
  await expect(page.getByRole("heading", { name: "Payments" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Accounts" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Reconciliation" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Operations" })).toHaveCount(0);
  const rows = page.getByRole("row").filter({ hasText: "INR" });
  await expect(rows).toHaveCount(2);
  for (const row of await rows.all()) await expect(row).toContainText(merchant);

  await page.goto("/accounts"); // a full page load: the in-memory token is gone, so the login form is back
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
});
