import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Load test for the rate-limiting gateway. Plain JDK, no dependencies:
 *
 *     JWT_SECRET=... java loadtest/LoadTest.java
 *
 * What it measures, and why each one:
 *   baseline    demo-service hit directly — the upstream on its own, for comparison
 *   allowed-N   the full gateway path at concurrency N: JWT check, Lua limiter,
 *               traffic event, proxy to upstream. Limit set high enough never to trip.
 *   rejected    requests the limiter turns away: JWT check + Lua only, no upstream.
 *               A limiter has to be cheapest exactly when it is under attack.
 *   accuracy-*  many users hammering concurrently; every user must be admitted
 *               exactly their limit, no more and no fewer.
 *
 * It never touches the databases. It signs its own tokens with JWT_SECRET, adds four
 * temporary rules for synthetic tenants ("lt-*") to the rules the gateway reads from
 * Redis, and on exit restores the original rules, trims traffic:events back to its
 * original length, and deletes every limiter key it created.
 *
 * Environment: JWT_SECRET (required), GATEWAY_URL (http://localhost:8080),
 * DEMO_URL (http://localhost:8083), REDIS_HOST (localhost), REDIS_PORT (6379),
 * REDIS_PASSWORD, DURATION_SECONDS (20).
 * Arguments: scenario names to run a subset, e.g. "allowed-64 rejected".
 *
 * Caveat: this is a closed-loop generator — each worker waits for its response before
 * sending the next — so under saturation it under-reports tail latency (coordinated
 * omission). Treat percentiles near the saturation point as optimistic.
 */
public class LoadTest {

    static final String GATEWAY = env("GATEWAY_URL", "http://localhost:8080");
    static final String DEMO = env("DEMO_URL", "http://localhost:8083");
    static final int DURATION = Integer.parseInt(env("DURATION_SECONDS", "20"));
    static final String TIER = "TIER_LT";
    static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    /** Consecutive readiness passes needed: enough to reach every instance behind a balancer. */
    static final int READY_PASSES = 10;

    static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) throws Exception {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            System.err.println("JWT_SECRET must be set (the same value the gateway uses).");
            System.exit(2);
        }
        Set<String> only = new HashSet<>(Arrays.asList(args));
        Jwt jwt = new Jwt(secret);

        try (Redis redis = Redis.connect()) {
            Fixture fixture = new Fixture(redis);
            fixture.install();
            List<Result> results = new ArrayList<>();
            try {
                waitForRules(jwt);
                printHeader();

                if (want(only, "baseline")) {
                    results.add(throughput("baseline (direct to upstream, no gateway)", 64, null, DEMO + "/demo/ping", jwt));
                }
                // Warm the JIT before any measured gateway run.
                throughput("warm-up", 64, "lt-open", GATEWAY + "/api/demo/ping", jwt);

                for (int c : new int[]{16, 64, 128}) {
                    if (want(only, "allowed-" + c)) {
                        results.add(throughput("allowed, concurrency " + c, c, "lt-open", GATEWAY + "/api/demo/ping", jwt));
                    }
                }
                if (want(only, "rejected")) {
                    results.add(throughput("rejected (over limit), concurrency 64", 64, "lt-block", GATEWAY + "/api/demo/ping", jwt));
                }
                printTable(results);

                if (want(only, "accuracy-sw")) {
                    accuracy("sliding window", "lt-sw", 100, jwt);
                }
                if (want(only, "accuracy-tb")) {
                    accuracy("token bucket", "lt-tb", 20, jwt);
                }
            } finally {
                fixture.restore();
            }
        }
    }

    static boolean want(Set<String> only, String name) {
        return only.isEmpty() || only.contains(name);
    }

    // ---- scenarios ---------------------------------------------------------------------

    record Result(String name, int concurrency, long requests, double seconds,
                  Map<Integer, Long> statuses, long errors, long[] latNanos) {
        double rps() { return requests / seconds; }
        double pct(double p) {
            if (latNanos.length == 0) return 0;
            int i = (int) Math.ceil(p / 100.0 * latNanos.length) - 1;
            return latNanos[Math.max(0, Math.min(i, latNanos.length - 1))] / 1e6;
        }
    }

    /** Closed-loop: `concurrency` workers each send, wait, repeat, for DURATION seconds. */
    static Result throughput(String name, int concurrency, String tenant, String url, Jwt jwt)
            throws Exception {
        int seconds = name.equals("warm-up") ? 10 : DURATION;
        System.out.printf("  running %-44s ", name + " ...");
        System.out.flush();

        // Spread load across many users, as real traffic would, so no single sorted set
        // or bucket becomes the bottleneck.
        String[] tokens = new String[200];
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = tenant == null ? null : jwt.token(tenant, "u-" + RUN + "-" + i);
        }

        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        List<Callable<long[]>> workers = new ArrayList<>();
        ConcurrentHashMap<Integer, Long> statuses = new ConcurrentHashMap<>();
        AtomicInteger errors = new AtomicInteger();

        for (int w = 0; w < concurrency; w++) {
            final int worker = w;
            workers.add(() -> {
                LongList lat = new LongList();
                int n = worker;
                while (System.nanoTime() < deadline) {
                    String token = tokens[n++ % tokens.length];
                    HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10)).GET();
                    if (token != null) rb.header("Authorization", "Bearer " + token);
                    long t0 = System.nanoTime();
                    try {
                        HttpResponse<Void> resp = HTTP.send(rb.build(), HttpResponse.BodyHandlers.discarding());
                        lat.add(System.nanoTime() - t0);
                        statuses.merge(resp.statusCode(), 1L, Long::sum);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
                return lat.toArray();
            });
        }

        long start = System.nanoTime();
        List<long[]> parts = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<long[]> f : pool.invokeAll(workers)) parts.add(f.get());
        }
        double elapsed = (System.nanoTime() - start) / 1e9;

        long[] all = parts.stream().flatMapToLong(Arrays::stream).sorted().toArray();
        Result r = new Result(name, concurrency, all.length, elapsed, new TreeMap<>(statuses), errors.get(), all);
        System.out.printf("%,8.0f req/s%n", r.rps());
        return r;
    }

    /**
     * Every user gets `perUser` requests fired concurrently and shuffled together, so each
     * user's requests race both each other and everyone else's. Correct enforcement admits
     * exactly `limit` per user.
     */
    static void accuracy(String label, String tenant, int limit, Jwt jwt) throws Exception {
        int users = 50, perUser = 300, concurrency = 200;
        List<String> jobs = new ArrayList<>();
        Map<String, String> tokenOf = new HashMap<>();
        for (int u = 0; u < users; u++) {
            String user = "acc-" + RUN + "-" + u;
            tokenOf.put(user, jwt.token(tenant, user));
            for (int i = 0; i < perUser; i++) jobs.add(user);
        }
        Collections.shuffle(jobs, new Random(42));
        ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>(jobs);
        ConcurrentHashMap<String, AtomicInteger> admitted = new ConcurrentHashMap<>();
        AtomicInteger rejected = new AtomicInteger(), other = new AtomicInteger();

        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < concurrency; w++) {
                pool.submit(() -> {
                    String user;
                    while ((user = queue.poll()) != null) {
                        try {
                            HttpResponse<Void> r = HTTP.send(HttpRequest.newBuilder(URI.create(GATEWAY + "/api/demo/ping"))
                                    .header("Authorization", "Bearer " + tokenOf.get(user))
                                    .timeout(Duration.ofSeconds(10)).GET().build(),
                                    HttpResponse.BodyHandlers.discarding());
                            if (r.statusCode() == 200) admitted.computeIfAbsent(user, k -> new AtomicInteger()).incrementAndGet();
                            else if (r.statusCode() == 429) rejected.incrementAndGet();
                            else other.incrementAndGet();
                        } catch (Exception e) {
                            other.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
        }
        double secs = (System.nanoTime() - start) / 1e9;

        IntSummaryStatistics per = tokenOf.keySet().stream()
                .mapToInt(u -> admitted.getOrDefault(u, new AtomicInteger()).get()).summaryStatistics();
        long wrong = tokenOf.keySet().stream()
                .filter(u -> admitted.getOrDefault(u, new AtomicInteger()).get() != limit).count();

        System.out.println();
        System.out.printf("  Accuracy — %s, limit %d per user%n", label, limit);
        System.out.printf("    %,d requests from %d users at concurrency %d, in %.1fs (%,.0f req/s)%n",
                jobs.size(), users, concurrency, secs, jobs.size() / secs);
        System.out.printf("    admitted per user: min %d, max %d  ->  %d of %d users off-limit%n",
                per.getMin(), per.getMax(), wrong, users);
        System.out.printf("    totals: %,d admitted, %,d rejected (429), %d other%n",
                per.getSum(), rejected.get(), other.get());
    }

    static void waitForRules(Jwt jwt) throws Exception {
        System.out.print("Waiting for the gateway to load the test rules (it refreshes every 30s) ");
        long until = System.currentTimeMillis() + 60_000;
        int attempt = 0;
        int passes = 0;
        while (System.currentTimeMillis() < until) {
            // lt-block allows 1 request per hour. Under the default rule a second request
            // would still pass, so a 429 on the second call proves our rules are live on
            // the instance that served it. Behind a load balancer each instance refreshes
            // on its own schedule, so one pass proves only one instance: require several
            // in a row, which round-robin spreads across every instance.
            String t = jwt.token("lt-block", "probe-" + RUN + "-" + attempt++);
            int first = status(GATEWAY + "/api/demo/ping", t);
            int second = status(GATEWAY + "/api/demo/ping", t);
            if (first == 401) throw new IllegalStateException("Gateway rejected the minted token — is JWT_SECRET the gateway's?");
            if (first == 200 && second == 429) {
                if (++passes >= READY_PASSES) {
                    System.out.println("ready.");
                    return;
                }
                continue;
            }
            passes = 0;
            System.out.print(".");
            Thread.sleep(3_000);
        }
        throw new IllegalStateException("Gateway did not pick up the test rules within 60s");
    }

    static int status(String url, String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    // ---- reporting -----------------------------------------------------------------------

    static void printHeader() {
        Runtime rt = Runtime.getRuntime();
        System.out.println();
        System.out.println("Load test " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        System.out.printf("  gateway %s, upstream %s, %ds per run, %d CPUs visible, JDK %s%n",
                GATEWAY, DEMO, DURATION, rt.availableProcessors(), System.getProperty("java.version"));
        System.out.println("  Everything — generator, gateway, upstream, Redis — shares one machine.");
        System.out.println();
    }

    static void printTable(List<Result> results) {
        System.out.println();
        System.out.println("| Scenario | Conc. | Requests | Req/s | p50 ms | p95 ms | p99 ms | max ms | Status | Errors |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|---|---:|");
        for (Result r : results) {
            StringJoiner st = new StringJoiner(" ");
            r.statuses().forEach((code, n) -> st.add(code + "×" + String.format("%,d", n)));
            System.out.printf("| %s | %d | %,d | %,.0f | %.2f | %.2f | %.2f | %.1f | %s | %d |%n",
                    r.name(), r.concurrency(), r.requests(), r.rps(),
                    r.pct(50), r.pct(95), r.pct(99), r.pct(100), st, r.errors());
        }
    }

    // ---- fixture: temporary rules, restored on exit ---------------------------------------

    static final class Fixture {
        private final Redis redis;
        private String originalRules;
        private long originalEvents;

        Fixture(Redis redis) { this.redis = redis; }

        void install() throws IOException {
            originalRules = (String) redis.cmd("GET", "rules:all");
            originalEvents = (Long) redis.cmd("LLEN", "traffic:events");
            String base = originalRules == null ? "[]" : originalRules.trim();

            String extra = String.join(",",
                    // Effectively unlimited; a 1s window keeps each user's sorted set tiny.
                    rule("lt-open", "SLIDING_WINDOW", 10_000_000, 1_000, 1),
                    rule("lt-block", "SLIDING_WINDOW", 1, 3_600_000, 1),
                    // Hour-long windows so the run's own duration cannot change the answer.
                    rule("lt-sw", "SLIDING_WINDOW", 100, 3_600_000, 1),
                    rule("lt-tb", "TOKEN_BUCKET", 1, 3_600_000, 20));
            String merged = base.equals("[]") ? "[" + extra + "]"
                    : base.substring(0, base.length() - 1) + "," + extra + "]";
            redis.cmd("SET", "rules:all", merged);
            System.out.printf("Installed 4 temporary rules (traffic:events backlog before: %d)%n", originalEvents);
        }

        void restore() throws IOException {
            if (originalRules == null) redis.cmd("DEL", "rules:all");
            else redis.cmd("SET", "rules:all", originalRules);

            // The gateway appends one event per request; cut back to exactly what was there.
            if (originalEvents == 0) redis.cmd("DEL", "traffic:events");
            else redis.cmd("LTRIM", "traffic:events", "0", String.valueOf(originalEvents - 1));

            int deleted = 0;
            for (String pattern : List.of("rl:sw:lt-*", "rl:tb:lt-*")) {
                String cursor = "0";
                do {
                    List<?> reply = (List<?>) redis.cmd("SCAN", cursor, "MATCH", pattern, "COUNT", "1000");
                    cursor = (String) reply.get(0);
                    for (Object key : (List<?>) reply.get(1)) {
                        redis.cmd("DEL", (String) key);
                        deleted++;
                    }
                } while (!cursor.equals("0"));
            }
            System.out.printf("%nRestored original rules, trimmed traffic:events to %d, deleted %d limiter keys.%n",
                    (Long) redis.cmd("LLEN", "traffic:events"), deleted);
        }

        static String rule(String tenant, String algorithm, int limit, long windowMs, int burst) {
            return String.format("{\"tenantId\":\"%s\",\"tier\":\"%s\",\"algorithm\":\"%s\",\"requestLimit\":%d,"
                    + "\"windowMs\":%d,\"burstCapacity\":%d,\"active\":true}", tenant, TIER, algorithm, limit, windowMs, burst);
        }
    }

    // ---- JWT: signs exactly as the auth service does ---------------------------------------

    static final class Jwt {
        private final byte[] key;
        private final String alg, macAlg;

        Jwt(String secret) {
            key = secret.getBytes(StandardCharsets.UTF_8);
            // Mirrors io.jsonwebtoken.security.Keys.hmacShaKeyFor: strongest HMAC the key supports.
            if (key.length >= 64) { alg = "HS512"; macAlg = "HmacSHA512"; }
            else if (key.length >= 48) { alg = "HS384"; macAlg = "HmacSHA384"; }
            else { alg = "HS256"; macAlg = "HmacSHA256"; }
        }

        String token(String tenant, String user) {
            long now = System.currentTimeMillis() / 1000;
            String header = "{\"alg\":\"" + alg + "\"}";
            String payload = String.format("{\"sub\":\"%s\",\"tenant_id\":\"%s\",\"tenant_uid\":\"loadtest\","
                    + "\"tier\":\"%s\",\"roles\":[\"ROLE_USER\"],\"username\":\"%s\",\"type\":\"ACCESS\","
                    + "\"iat\":%d,\"exp\":%d}", user, tenant, TIER, user, now, now + 3600);
            String signingInput = b64(header) + "." + b64(payload);
            try {
                Mac mac = Mac.getInstance(macAlg);
                mac.init(new SecretKeySpec(key, macAlg));
                return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static String b64(String s) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---- minimal Redis client (RESP2) --------------------------------------------------------

    static final class Redis implements AutoCloseable {
        private final Socket socket;
        private final OutputStream out;
        private final InputStream in;

        private Redis(Socket s) throws IOException {
            socket = s;
            out = s.getOutputStream();
            in = new BufferedInputStream(s.getInputStream());
        }

        static Redis connect() throws IOException {
            Redis r = new Redis(new Socket(env("REDIS_HOST", "localhost"), Integer.parseInt(env("REDIS_PORT", "6379"))));
            String pw = System.getenv("REDIS_PASSWORD");
            if (pw != null && !pw.isBlank()) r.cmd("AUTH", pw);
            return r;
        }

        Object cmd(String... args) throws IOException {
            StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
            for (String a : args) {
                byte[] b = a.getBytes(StandardCharsets.UTF_8);
                sb.append('$').append(b.length).append("\r\n").append(a).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return read();
        }

        private Object read() throws IOException {
            int type = in.read();
            String line = line();
            switch (type) {
                case '+': return line;
                case '-': throw new IOException("Redis error: " + line);
                case ':': return Long.parseLong(line);
                case '$': {
                    int len = Integer.parseInt(line);
                    if (len < 0) return null;
                    byte[] b = in.readNBytes(len);
                    in.readNBytes(2);
                    return new String(b, StandardCharsets.UTF_8);
                }
                case '*': {
                    int n = Integer.parseInt(line);
                    if (n < 0) return null;
                    List<Object> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(read());
                    return list;
                }
                default: throw new IOException("Unexpected RESP type: " + (char) type);
            }
        }

        private String line() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != '\r') sb.append((char) c);
            in.read();
            return sb.toString();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    // ---- small helpers -------------------------------------------------------------------

    static final class LongList {
        private long[] a = new long[4096];
        private int n;
        void add(long v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
        long[] toArray() { return Arrays.copyOf(a, n); }
    }

    static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
