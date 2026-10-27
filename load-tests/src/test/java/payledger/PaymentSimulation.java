package payledger;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
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
 * Payments at one constant rate (env RATE, for STEP_SECONDS): each "user" creates a payment (the stand-in bank authorizes it) and captures it.
 * The capture's ledger posting happens afterwards through the outbox and Kafka; ops/load/run.sh checks that all of
 * them arrive. The summary counts both requests (create and capture) together.
 */
public class PaymentSimulation extends Simulation {

    public PaymentSimulation() {
        Seed seed = Seed.get();
        List<String> wallets = seed.wallets;

        var protocol = http.baseUrl(Seed.PAYMENTS)
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .authorizationHeader("Bearer " + seed.merchantToken)
                .shareConnections();   // one pool, like a real client: a new connection per user would exhaust this machine's ports first

        var payments = Stream.generate(() -> Map.<String, Object>of(
                "key", UUID.randomUUID().toString(),
                "payer", wallets.get(ThreadLocalRandom.current().nextInt(wallets.size())))).iterator();

        int rate = Seed.rate();
        String suffix = String.format("%04drps", rate);
        var scenario = scenario("payments " + rate + "/s")
                .feed(payments)
                .exec(http("create-" + suffix)
                        .post("/payments")
                        .header("Idempotency-Key", "#{key}")
                        .body(StringBody("{\"merchantId\":\"" + seed.merchant
                                + "\",\"payerAccountId\":\"#{payer}\",\"amount\":1.00,\"currency\":\"INR\"}"))
                        .check(status().is(201), jsonPath("$.id").saveAs("paymentId")))
                .exec(http("capture-" + suffix)
                        .post("/payments/#{paymentId}/capture")
                        .check(status().is(200)));
        setUp(scenario.injectOpen(constantUsersPerSec(rate).during(Duration.ofSeconds(Seed.STEP_SECONDS)))).protocols(protocol);
    }
}
