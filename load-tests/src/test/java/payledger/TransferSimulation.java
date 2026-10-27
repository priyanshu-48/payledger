package payledger;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Simulation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Peer-to-peer transfers at one constant request rate (env RATE, for STEP_SECONDS). ops/load/run.sh runs it once per
 * rate and ops/load/summarize.py reads the console summary of each run.
 * Wallets are picked at random from a pool of 200, so this measures the API and database, not one hot account.
 */
public class TransferSimulation extends Simulation {

    public TransferSimulation() {
        Seed seed = Seed.get();
        List<String> wallets = seed.wallets;

        var protocol = http.baseUrl(Seed.LEDGER)
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .authorizationHeader("Bearer " + seed.adminToken)
                .shareConnections();   // one pool, like a real client: a new connection per user would exhaust this machine's ports first

        var transfers = Stream.generate(() -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int from = random.nextInt(wallets.size());
            int to = (from + 1 + random.nextInt(wallets.size() - 1)) % wallets.size();
            return Map.<String, Object>of("key", UUID.randomUUID().toString(), "from", wallets.get(from), "to", wallets.get(to));
        }).iterator();

        int rate = Seed.rate();
        var scenario = scenario("transfers " + rate + "/s")
                .feed(transfers)
                .exec(http(String.format("transfer-%04drps", rate))
                        .post("/transfers")
                        .header("Idempotency-Key", "#{key}")
                        .body(StringBody("{\"fromAccountId\":\"#{from}\",\"toAccountId\":\"#{to}\",\"amount\":1.00,\"currency\":\"INR\"}"))
                        .check(status().is(201)));
        setUp(scenario.injectOpen(constantUsersPerSec(rate).during(Duration.ofSeconds(Seed.STEP_SECONDS)))).protocols(protocol);
    }
}
