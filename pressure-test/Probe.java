import java.net.URI;
import java.net.http.*;

/**
 * 最小链路探测器：逐步验证登录 → 取幂等Token → 秒杀，打印每一步的真实响应。
 * 用于定位压测中"系统异常"的根因。
 */
public class Probe {
    public static void main(String[] a) throws Exception {
        HttpClient c = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:8080";
        String user = a.length > 0 ? a[0] : "wangwu";

        System.out.println("========== Step 1: 登录 ==========");
        HttpRequest login = HttpRequest.newBuilder()
                .uri(URI.create(base + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + user + "\",\"password\":\"123456\"}"))
                .timeout(java.time.Duration.ofSeconds(10))
                .build();
        HttpResponse<String> lr = c.send(login, HttpResponse.BodyHandlers.ofString());
        System.out.println("STATUS=" + lr.statusCode());
        System.out.println("BODY=" + lr.body());

        String jwt = extract(lr.body(), "data");
        System.out.println("JWT_LEN=" + (jwt == null ? -1 : jwt.length()));
        if (jwt == null) {
            System.out.println(">>> 登录失败，终止");
            return;
        }

        System.out.println();
        System.out.println("========== Step 2: 获取幂等Token ==========");
        HttpRequest tr = HttpRequest.newBuilder()
                .uri(URI.create(base + "/seckill/token?activityId=1"))
                .header("Authorization", "Bearer " + jwt)
                .GET()
                .timeout(java.time.Duration.ofSeconds(10))
                .build();
        HttpResponse<String> trr = c.send(tr, HttpResponse.BodyHandlers.ofString());
        System.out.println("STATUS=" + trr.statusCode());
        System.out.println("BODY=" + trr.body());

        String idem = extract(trr.body(), "data");
        System.out.println("IDEM_TOKEN=" + idem);
        if (idem == null) {
            System.out.println(">>> 取 Token 失败，终止");
            return;
        }

        System.out.println();
        System.out.println("========== Step 3: 执行秒杀 ==========");
        HttpRequest sr = HttpRequest.newBuilder()
                .uri(URI.create(base + "/seckill/do"))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"activityId\":1,\"idempotentToken\":\"" + idem + "\"}"))
                .timeout(java.time.Duration.ofSeconds(10))
                .build();
        HttpResponse<String> srr = c.send(sr, HttpResponse.BodyHandlers.ofString());
        System.out.println("STATUS=" + srr.statusCode());
        System.out.println("BODY=" + srr.body());

        System.out.println();
        System.out.println("========== Step 4: 等待 1 秒后查询结果 ==========");
        Thread.sleep(1000);
        HttpRequest qr = HttpRequest.newBuilder()
                .uri(URI.create(base + "/seckill/result?activityId=1"))
                .header("Authorization", "Bearer " + jwt)
                .GET()
                .timeout(java.time.Duration.ofSeconds(10))
                .build();
        HttpResponse<String> qrr = c.send(qr, HttpResponse.BodyHandlers.ofString());
        System.out.println("STATUS=" + qrr.statusCode());
        System.out.println("BODY=" + qrr.body());
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
}
