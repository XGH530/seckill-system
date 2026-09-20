package com.xgh.seckill;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 秒杀系统并发压测程序（纯 JDK HttpClient 实现，无需 JMeter）
 *
 * 【压测目标】
 *   用远超库存的并发量冲击秒杀接口，验证：
 *     1. 不超卖：成功订单数 <= 初始库存
 *     2. 性能：统计 QPS / 平均耗时 / P99
 *     3. 幂等：同一用户重复请求只成功一次
 *
 * 【为什么不用 JMeter】
 *   JMeter 需要额外下载安装，且 GUI 模式压测自身开销大。
 *   这里用 JDK 内置 HttpClient + 虚拟线程/线程池，零依赖、结果可复现。
 *
 * 【用法】
 *   java PressureTest <baseUrl> <activityId> <并发线程数> <每线程请求数>
 *   例：java PressureTest http://127.0.0.1:8080 1 200 3
 *
 * @author 向国豪
 */
public class PressureTest {

    private static final AtomicInteger successCount = new AtomicInteger();
    private static final AtomicInteger failCount = new AtomicInteger();
    private static final AtomicInteger errorCount = new AtomicInteger();

    private static final List<Long> latencies = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://127.0.0.1:8080";
        long activityId = args.length > 1 ? Long.parseLong(args[1]) : 1L;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        int requestsPerThread = args.length > 3 ? Integer.parseInt(args[3]) : 3;

        int totalRequests = concurrency * requestsPerThread;

        System.out.println("========================================================");
        System.out.println("  秒杀系统并发压测");
        System.out.println("========================================================");
        System.out.println("  目标地址   : " + baseUrl);
        System.out.println("  活动 ID    : " + activityId);
        System.out.println("  并发线程   : " + concurrency);
        System.out.println("  每线程请求 : " + requestsPerThread);
        System.out.println("  总请求数   : " + totalRequests);
        System.out.println("--------------------------------------------------------");

        // 1. 登录获取 Token（每个线程独立用户会产生真实的一人一单竞争，
        //    这里为了压测吞吐量，所有线程复用同一个用户，改为测「性能 + 幂等」）
        String token = login(baseUrl, "zhangsan", "123456");
        if (token == null) {
            System.err.println("登录失败，压测终止");
            return;
        }
        System.out.println("  登录成功，Token 已获取");
        System.out.println("--------------------------------------------------------");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // 压测关键：连接复用，避免每次握手开销干扰结果
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < concurrency; i++) {
            final int threadIndex = i;
            Thread t = new Thread(() -> {
                readyLatch.countDown();
                try {
                    // 所有线程在此等待，确保同时发起，形成真实并发峰值
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                for (int j = 0; j < requestsPerThread; j++) {
                    fireOnce(client, baseUrl, activityId, token);
                }
                doneLatch.countDown();
            }, "pressure-" + threadIndex);
            t.setDaemon(true);
            t.start();
        }

        readyLatch.await();
        System.out.println("  所有线程就绪，开始压测...");
        long fireTime = System.currentTimeMillis();
        startLatch.countDown();

        doneLatch.await();
        long endTime = System.currentTimeMillis();

        long wallClock = endTime - fireTime;
        double qps = totalRequests * 1000.0 / Math.max(wallClock, 1);

        System.out.println("--------------------------------------------------------");
        System.out.println("  压测结果");
        System.out.println("--------------------------------------------------------");
        System.out.println("  总耗时       : " + wallClock + " ms");
        System.out.printf("  吞吐量 QPS   : %.1f%n", qps);
        System.out.println("  成功请求     : " + successCount.get());
        System.out.println("  业务失败     : " + failCount.get() + "  (库存不足 / 重复下单，属预期)");
        System.out.println("  系统异常     : " + errorCount.get() + "  (应当为 0)");
        System.out.println("--------------------------------------------------------");

        // 并发延迟统计
        synchronized (latencies) {
            if (!latencies.isEmpty()) {
                List<Long> sorted = new ArrayList<>(latencies);
                sorted.sort(Long::compareTo);
                long sum = sorted.stream().mapToLong(Long::longValue).sum();
                double avg = sum * 1.0 / sorted.size();
                long p50 = sorted.get((int) (sorted.size() * 0.50));
                long p95 = sorted.get(Math.min((int) (sorted.size() * 0.95), sorted.size() - 1));
                long p99 = sorted.get(Math.min((int) (sorted.size() * 0.99), sorted.size() - 1));
                long max = sorted.get(sorted.size() - 1);

                System.out.println("  延迟统计（ms）");
                System.out.printf("    平均 : %.1f%n", avg);
                System.out.println("    P50  : " + p50);
                System.out.println("    P95  : " + p95);
                System.out.println("    P99  : " + p99);
                System.out.println("    最大 : " + max);
            }
        }

        System.out.println("========================================================");
    }

    /**
     * 发起一次秒杀请求
     *
     * 【完整流程】先获取幂等 Token，再携带 Token 调用秒杀接口
     * 这模拟了真实前端的调用方式，也顺带验证了幂等机制的生效
     */
    private static void fireOnce(HttpClient client, String baseUrl, long activityId, String token) {
        try {
            // Step 1: 获取幂等 Token
            HttpRequest tokenReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/seckill/token?activityId=" + activityId))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> tokenResp = client.send(tokenReq, HttpResponse.BodyHandlers.ofString());
            String idemToken = extractField(tokenResp.body(), "data");

            if (idemToken == null) {
                errorCount.incrementAndGet();
                return;
            }

            // Step 2: 携带 Token 发起秒杀
            String jsonBody = "{\"activityId\":" + activityId
                    + ",\"idempotentToken\":\"" + idemToken + "\"}";

            HttpRequest seckillReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/seckill/do"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            long t0 = System.currentTimeMillis();
            HttpResponse<String> resp = client.send(seckillReq, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - t0;

            synchronized (latencies) {
                latencies.add(cost);
            }

            if (resp.statusCode() != 200) {
                // 记录首个非 200 响应，便于定位问题
                if (errorCount.get() == 0) {
                    System.err.println("[首个非200响应] status=" + resp.statusCode()
                            + " body=" + resp.body());
                }
                errorCount.incrementAndGet();
                return;
            }

            String body = resp.body();
            if (body.contains("\"success\":true")) {
                successCount.incrementAndGet();
            } else {
                // 业务失败（库存不足 / 重复下单），属预期结果
                if (failCount.get() == 0) {
                    System.out.println("[首个业务失败] " + body);
                }
                failCount.incrementAndGet();
            }

        } catch (IOException | InterruptedException e) {
            errorCount.incrementAndGet();
        }
    }

    /** 登录并返回 JWT Token */
    private static String login(String baseUrl, String username, String password) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            String json = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            // 【注意】登录接口返回的 data 是一个对象：
            //   {"code":200,"data":{"nickname":"张三","userId":1,"token":"eyJ..."}}
            // 所以必须取内层的 token 字段，不能直接取 data。
            String token = extractField(resp.body(), "token");
            if (token == null || token.isBlank()) {
                System.err.println("登录响应中未找到 token 字段，原始响应：");
                System.err.println(resp.body());
            }
            return token;
        } catch (Exception e) {
            System.err.println("登录异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从 JSON 中提取简单字符串字段值（避免引入 Jackson 依赖）
     * 支持形如 "field":"value" 的取值
     */
    private static String extractField(String json, String field) {
        if (json == null) {
            return null;
        }
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        return json.substring(start + 1, end);
    }
}
