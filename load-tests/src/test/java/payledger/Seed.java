package payledger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test data, created through the public API before the load starts: a settlement account, a merchant (user and
 * ledger account) and a pool of funded wallets. Addresses and the admin password come from the environment, so the
 * same simulations can target a local stack or a deployed one.
 */
final class Seed {

    static final String AUTH = env("AUTH_URL", "http://localhost:8084");
    static final String LEDGER = env("LEDGER_URL", "http://localhost:8082");
    static final String PAYMENTS = env("PAYMENTS_URL", "http://localhost:8081");
    static final int WALLETS = Integer.parseInt(env("WALLETS", "200"));
    static final int STEP_SECONDS = Integer.parseInt(env("STEP_SECONDS", "30"));

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TOKEN = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");

    final String adminToken;
    final String merchantToken;
    final String merchant;
    final List<String> wallets = new ArrayList<>();

    private static Seed instance;

    /** One seed per simulation JVM; created on first use. */
    static synchronized Seed get() {
        if (instance == null) {
            instance = new Seed();
        }
        return instance;
    }

    private Seed() {
        try {
            adminToken = login("admin", env("AUTH_ADMIN_PASSWORD", ""));
            Path file = Path.of(env("SEED_FILE", "target/seed.txt"));
            if (Files.exists(file)) {              // an earlier run of this load test already created the data
                List<String> lines = Files.readAllLines(file);
                merchant = lines.get(0);
                merchantToken = login(merchant, lines.get(1));
                wallets.addAll(lines.subList(2, lines.size()));
                return;
            }
            String run = Long.toString(System.currentTimeMillis(), 36);
            merchant = "load-merchant-" + run;
            byte[] random = new byte[12];
            new SecureRandom().nextBytes(random);
            String merchantPassword = HexFormat.of().formatHex(random);
            send("POST", AUTH + "/auth/users", adminToken, null,
                    "{\"username\":\"" + merchant + "\",\"password\":\"" + merchantPassword + "\",\"role\":\"MERCHANT\"}");
            merchantToken = login(merchant, merchantPassword);

            String settlement = id(send("POST", LEDGER + "/accounts", adminToken, null, account("load-settlement-" + run, "SETTLEMENT")));
            send("POST", LEDGER + "/accounts", adminToken, null, account(merchant, "MERCHANT"));
            for (int i = 0; i < WALLETS; i++) {
                String wallet = id(send("POST", LEDGER + "/accounts", adminToken, null, account("load-wallet-" + run + "-" + i, "USER_WALLET")));
                send("POST", LEDGER + "/transfers", adminToken, "fund-" + run + "-" + i,
                        "{\"fromAccountId\":\"" + settlement + "\",\"toAccountId\":\"" + wallet + "\",\"amount\":1000000.00,\"currency\":\"INR\"}");
                wallets.add(wallet);
            }
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.write(file, java.util.stream.Stream.concat(java.util.stream.Stream.of(merchant, merchantPassword), wallets.stream()).toList());
            System.out.println("seed: merchant " + merchant + ", " + wallets.size() + " funded wallets");
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("could not seed the stack: " + e, e);
        }
    }

    private static String account(String owner, String type) {
        return "{\"ownerId\":\"" + owner + "\",\"type\":\"" + type + "\",\"currency\":\"INR\"}";
    }

    private static String login(String user, String password) throws IOException, InterruptedException {
        String body = send("POST", AUTH + "/auth/token", null, null, "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}");
        Matcher m = TOKEN.matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("login failed for " + user);
        }
        return m.group(1);
    }

    private static String id(String json) {
        Matcher m = ID.matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("no id in " + json);
        }
        return m.group(1);
    }

    private static String send(String method, String url, String token, String idempotencyKey, String json)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.header("Idempotency-Key", idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString());
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException(method + " " + url + " -> " + response.statusCode());
        }
        return response.body();
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** The rate of this run, in requests (or payments) per second. */
    static int rate() {
        return Integer.parseInt(env("RATE", "10"));
    }
}
