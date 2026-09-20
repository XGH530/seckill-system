import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 多用户并发压测（真实超卖验证）
 *
 * 【与 PressureTest 的区别】
 *   PressureTest 用单一用户 —— 只能验证「幂等/一人一单」。
 *   本程序用【每个线程独立用户】—— 才能真正压出库存竞争，
 *   验证「100 库存 vs N 并发」下是否超卖。
 *
 * 【输出全部为 ASCII】，避免中文编码问题。
 *
 * 用法：java MultiUserPressureTest <baseUrl> <activityId> <并发用户数>
 */
public class MultiUserPressureTest {

    private static final AtomicInteger successCount = new AtomicInteger();
    private static final AtomicInteger failCount = new AtomicInteger();
    private static final AtomicInteger errorCount = new AtomicInteger();
    private static final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    private static final Map<Integer, Integer> codeDistribution = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://127.0.0.1:8080";
        long activityId = args.length > 1 ? Long.parseLong(args[1]) : 1L;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 200;

        PrintWriter out = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream("pressure-test/multiuser-result.txt"),
                StandardCharsets.US_ASCII), true);

        log(out, "========================================================");
        log(out, "  MULTI-USER CONCURRENCY TEST");
        log(out, "========================================================");
        log(out, "  Target      : " + baseUrl);
        log(out, "  ActivityId  : " + activityId);
        log(out, "  Concurrency : " + concurrency);
        log(out, "--------------------------------------------------------");

        // 读取活动初始库存
        int initialStock = queryStock(baseUrl, activityId);
        log(out, "  InitialStock(DB read via API): " + initialStock);
        log(out, "--------------------------------------------------------");

        // 为每个线程准备独立用户（用户名 u1..uN，密码统一 123456）
        // 需要一个注册接口；若没有，则复用已有 3 个用户 + 直接生成 JWT 的方式不可行。
        // 这里改为：先尝试注册，失败则说明系统无注册接口 → 用现有用户做分组压测。
        log(out, "  Preparing users...");
        List<String> tokens = new ArrayList<>();
        for (int i = 1; i <= concurrency; i++) {
            String user = "u" + i;
            String token = registerOrLogin(baseUrl, user, "123456");
            if (token != null) {
                tokens.add(token);
            }
        }
        log(out, "  Users ready: " + tokens.size() + " / " + concurrency);

        if (tokens.isEmpty()) {
            log(out, "  >>> No token available, abort.");
            out.close();
            return;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        int actualConcurrency = tokens.size();
        CountDownLatch ready = new CountDownLatch(actualConcurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(actualConcurrency);

        for (int i = 0; i < actualConcurrency; i++) {
            final String token = tokens.get(i);
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                fire(client, baseUrl, activityId, token, out);
                done.countDown();
            });
            t.setDaemon(true);
            t.start();
        }

        ready.await();
        log(out, "  All threads ready. Firing...");
        long t0 = System.currentTimeMillis();
        start.countDown();
        done.await();
        long cost = System.currentTimeMillis() - t0;

        double qps = actualConcurrency * 1000.0 / Math.max(cost, 1);

        log(out, "--------------------------------------------------------");
        log(out, "  RESULT");
        log(out, "--------------------------------------------------------");
        log(out, "  WallClock    : " + cost + " ms");
        log(out, String.format("  QPS          : %.1f", qps));
        log(out, "  Success      : " + successCount.get());
        log(out, "  BizFail      : " + failCount.get());
        log(out, "  SysError     : " + errorCount.get() + "   (MUST be 0)");
        log(out, "--------------------------------------------------------");
        log(out, "  Response code distribution:");
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(codeDistribution).entrySet()) {
            log(out, "    code " + e.getKey() + " -> " + e.getValue());
        }

        // 压测后查询 Redis 剩余库存
        Thread.sleep(1500);
        int remainStock = queryRedisStock(baseUrl, activityId);
        log(out, "--------------------------------------------------------");
        log(out, "  OVERSELL CHECK");
        log(out, "--------------------------------------------------------");
        log(out, "  Initial stock    : " + initialStock);
        log(out, "  Success orders   : " + successCount.get());
        log(out, "  Remaining stock  : " + remainStock);
        boolean oversell = successCount.get() > initialStock;
        log(out, "  OVERSELL         : " + (oversell ? "*** YES - FAILED ***" : "NO - PASSED"));
        log(out, "========================================================");

        out.close();
    }

    private static void fire(HttpClient client, String baseUrl, long activityId,
                             String token, PrintWriter out) {
        try {
            // 1. 取幂等 token
            HttpRequest tr = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/seckill/token?activityId=" + activityId))
                    .header("Authorization", "Bearer " + token)
                    .GET().timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> trr = client.send(tr, HttpResponse.BodyHandlers.ofString());
            String idem = extract(trr.body(), "data");
            if (idem == null) {
                errorCount.incrementAndGet();
                return;
            }

            // 2. 秒杀
            HttpRequest sr = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/seckill/do"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"activityId\":" + activityId + ",\"idempotentToken\":\"" + idem + "\"}"))
                    .timeout(Duration.ofSeconds(10)).build();

            long s0 = System.currentTimeMillis();
            HttpResponse<String> resp = client.send(sr, HttpResponse.BodyHandlers.ofString());
            latencies.add(System.currentTimeMillis() - s0);

            if (resp.statusCode() != 200) {
                errorCount.incrementAndGet();
                return;
            }

            String body = resp.body();
            int code = extractInt(body, "code");
            codeDistribution.merge(code, 1, Integer::sum);

            if (body.contains("\"success\":true")) {
                successCount.incrementAndGet();
            } else {
                failCount.incrementAndGet();
            }
        } catch (Exception e) {
            errorCount.incrementAndGet();
        }
    }

    /** 优先注册，失败则登录 */
    private static String registerOrLogin(String baseUrl, String user, String pwd) {
        try {
            HttpClient c = HttpClient.newHttpClient();
            String json = "{\"username\":\"" + user + "\",\"password\":\"" + pwd + "\"}";

            // 尝试注册
            HttpRequest reg = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/auth/register"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10)).build();
            try {
                c.send(reg, HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {
                // 注册接口不存在时忽略，直接登录
            }

            // 登录
            HttpRequest login = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> lr = c.send(login, HttpResponse.BodyHandlers.ofString());
            return extract(lr.body(), "token");
        } catch (Exception e) {
            return null;
        }
    }

    /** 通过接口读取活动初始库存 */
    private static int queryStock(String baseUrl, long activityId) {
        try {
            HttpClient c = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/goods/activity/" + activityId))
                    .GET().timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> resp = c.send(req, HttpResponse.BodyHandlers.ofString());
            return extractInt(resp.body(), "initialStock");
        } catch (Exception e) {
            return -1;
        }
    }

    /** 直接读 Redis 剩余库存（通过 actuator 不可用，故用简单方式：再次预热接口前的探测） */
    private static int queryRedisStock(String baseUrl, long activityId) {
        // 通过多次调用 result 接口无法拿库存，这里返回 -1 表示未获取
        // 真实库存由外部 redis-cli 验证，本程序只做订单数统计
        return -1;
    }

    private static void log(PrintWriter out, String s) {
        System.out.println(s);
        out.println(s);
    }

    private static String extract(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static int extractInt(String json, String field) {
        if (json == null) return -1;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return -1;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return -1;
        int i = colon + 1;
        while (i < json.length() && !Character.isDigit(json.charAt(i)) && json.charAt(i) != '-') i++;
        int start = i;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) i++;
        if (i == start) return -1;
        try {
            return Integer.parseInt(json.substring(start, i));
        } catch (Exception e) {
            return -1;
        }
    }
}
