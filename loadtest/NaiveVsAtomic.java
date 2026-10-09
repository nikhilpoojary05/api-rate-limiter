import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Why the limiter is a Lua script: the gateway's sliding-window logic, run two ways
 * against the same Redis under the same concurrent load.
 *
 * <pre>
 *   separate commands   ZREMRANGEBYSCORE, ZCARD, then ZADD if under the limit: three
 *                       round trips, so concurrent requests can all read "under the
 *                       limit" before any of them adds its entry
 *   one Lua script      the gateway's own sliding_window.lua: the same commands, run
 *                       atomically inside Redis
 * </pre>
 *
 * Plain JDK, talks RESP directly, touches only its own "exp:*" keys and deletes them:
 *
 *     REDIS_PORT=6380 REDIS_PASSWORD=... java loadtest/NaiveVsAtomic.java
 *
 * Two loads: 50 users racing a few requests each at a time, and one user racing 200 at a
 * time on a single key.
 *
 * Environment: REDIS_HOST (localhost), REDIS_PORT (6379), REDIS_PASSWORD, RUNS (3).
 */
public class NaiveVsAtomic {
    static final String HOST = env("REDIS_HOST", "localhost");
    static final int PORT = Integer.parseInt(env("REDIS_PORT", "6379"));
    static final String PASSWORD = System.getenv("REDIS_PASSWORD");
    static final int RUNS = Integer.parseInt(env("RUNS", "3"));
    static final int CONCURRENCY = 200, LIMIT = 100;
    static final long WINDOW_MS = 60_000;

    public static void main(String[] args) throws Exception {
        String script = Files.readString(Path.of("gateway-service/src/main/resources/scripts/sliding_window.lua"));
        // Many users: each user's requests race a few at a time, as in the gateway's
        // accuracy test. One user: everything races on one key, the worst case, such as
        // a single client or attacker bursting.
        int[][] scenarios = {{50, 300}, {1, 2_000}};
        for (int[] sc : scenarios) {
            System.out.printf("%n%d user(s) x %,d requests at concurrency %d, limit %d per user per minute, %d runs each%n%n",
                    sc[0], sc[1], CONCURRENCY, LIMIT, RUNS);
            System.out.println("| Variant | Run | Admitted per user (min / avg / max) | Users over the limit | Requests over the limit |");
            System.out.println("|---|---:|---|---:|---:|");
            for (String variant : List.of("separate commands", "one Lua script")) {
                for (int run = 1; run <= RUNS; run++) {
                    run(variant, run, script, sc[0], sc[1]);
                }
            }
        }
    }

    static void run(String variant, int run, String script, int USERS, int PER_USER) throws Exception {
        String prefix = "exp:" + UUID.randomUUID().toString().substring(0, 8) + ":";
        List<String> jobs = new ArrayList<>();
        for (int u = 0; u < USERS; u++) {
            for (int i = 0; i < PER_USER; i++) jobs.add(prefix + u);
        }
        Collections.shuffle(jobs, new Random(42 + run));
        ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>(jobs);
        Map<String, AtomicInteger> admitted = new ConcurrentHashMap<>();
        AtomicInteger errors = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < CONCURRENCY; w++) {
                pool.submit(() -> {
                    try (Resp redis = Resp.connect()) {
                        String key;
                        while ((key = queue.poll()) != null) {
                            boolean ok = variant.equals("one Lua script")
                                    ? atomic(redis, key, script)
                                    : separateCommands(redis, key);
                            if (ok) admitted.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    return null;
                });
            }
        }

        IntSummaryStatistics per = admitted.values().stream().mapToInt(AtomicInteger::get).summaryStatistics();
        long usersOver = admitted.values().stream().filter(a -> a.get() > LIMIT).count();
        long requestsOver = admitted.values().stream().mapToLong(a -> Math.max(0, a.get() - LIMIT)).sum();
        System.out.printf("| %s | %d | %d / %.1f / %d | %d of %d | %,d |%n",
                variant, run, per.getMin(), per.getAverage(), per.getMax(), usersOver, USERS, requestsOver);
        if (errors.get() > 0) System.out.printf("  (%d worker errors)%n", errors.get());

        try (Resp redis = Resp.connect()) {
            for (int u = 0; u < USERS; u++) redis.call("DEL", prefix + u);
        }
    }

    /** The gateway's algorithm as three separate round trips. */
    static boolean separateCommands(Resp redis, String key) throws IOException {
        long now = System.currentTimeMillis();
        redis.call("ZREMRANGEBYSCORE", key, "0", String.valueOf(now - WINDOW_MS));
        long count = (Long) redis.call("ZCARD", key);
        if (count < LIMIT) {
            redis.call("ZADD", key, String.valueOf(now), now + "-" + UUID.randomUUID());
            redis.call("PEXPIRE", key, String.valueOf(WINDOW_MS));
            return true;
        }
        return false;
    }

    /** The gateway's own script; 0 makes it read Redis's clock, as in production. */
    static boolean atomic(Resp redis, String key, String script) throws IOException {
        List<?> result = (List<?>) redis.call("EVAL", script, "1", key,
                "0", String.valueOf(WINDOW_MS), String.valueOf(LIMIT), UUID.randomUUID().toString());
        return (Long) result.get(0) == 1L;
    }

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** Minimal RESP client: one connection, blocking calls. */
    static final class Resp implements AutoCloseable {
        private final Socket socket;
        private final OutputStream out;
        private final InputStream in;

        private Resp(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
            this.in = new BufferedInputStream(socket.getInputStream());
        }

        static Resp connect() throws IOException {
            Resp r = new Resp(new Socket(HOST, PORT));
            if (PASSWORD != null && !PASSWORD.isBlank()) r.call("AUTH", PASSWORD);
            return r;
        }

        Object call(String... args) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.writeBytes(("*" + args.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            for (String a : args) {
                byte[] b = a.getBytes(StandardCharsets.UTF_8);
                buf.writeBytes(("$" + b.length + "\r\n").getBytes(StandardCharsets.UTF_8));
                buf.writeBytes(b);
                buf.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(buf.toByteArray());
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
                    List<Object> items = new ArrayList<>();
                    for (int i = 0; i < n; i++) items.add(read());
                    return items;
                }
                default: throw new IOException("Unexpected RESP type " + (char) type);
            }
        }

        private String line() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != '\r') {
                if (c < 0) throw new IOException("Connection closed");
                sb.append((char) c);
            }
            in.read();
            return sb.toString();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
