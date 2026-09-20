import java.net.URI;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * ASCII-only 链路探测器。
 * 所有输出均为纯 ASCII，避免中文导致的编码/过滤问题。
 * 结果同时写入 probe-ascii.txt 与控制台。
 */
public class ProbeAscii {

    private static PrintWriter out;

    public static void main(String[] a) throws Exception {
        out = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream("pressure-test/probe-ascii.txt"),
                StandardCharsets.US_ASCII), true);

        HttpClient c = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:8080";
        String user = a.length > 0 ? a[0] : "wangwu";

        // Step 1: login
        HttpRequest login = HttpRequest.newBuilder()
                .uri(URI.create(base + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + user + "\",\"password\":\"123456\"}"))
                .timeout(java.time.Duration.ofSeconds(10))
                .build();
        HttpResponse<String> lr = c.send(login, HttpResponse.BodyHandlers.ofString());
        log("STEP1_LOGIN_STATUS=" + lr.statusCode());

        String jwt = extract(lr.body(), "token");
        log("STEP1_JWT_LEN=" + (jwt == null ? -1 : jwt.length()));
        if (jwt == null) {
            log("STEP1_FAILED body_len=" + lr.body().length());
            out.close();
            return;
        }

        // Step 2: idempotent token
        HttpRequest tr = HttpRequest.newBuilder()
                .uri(URI.create(base + "/seckill/token?activityId=1"))
                .header("Authorization", "Bearer " + jwt)
                .GET().timeout(java.time.Duration.ofSeconds(10)).build();
        HttpResponse<String> trr = c.send(tr, HttpResponse.BodyHandlers.ofString());
        log("STEP2_TOKEN_STATUS=" + trr.statusCode());
        String idem = extract(trr.body(), "data");
        log("STEP2_IDEM_TOKEN_LEN=" + (idem == null ? -1 : idem.length()));
        if (idem == null) {
            log("STEP2_FAILED body_len=" + trr.body().length());
            out.close();
            return;
        }

        // Step 3: seckill
        HttpRequest sr = HttpRequest.newBuilder()
                .uri(URI.create(base + "/seckill/do"))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"activityId\":1,\"idempotentToken\":\"" + idem + "\"}"))
                .timeout(java.time.Duration.ofSeconds(10)).build();
        HttpResponse<String> srr = c.send(sr, HttpResponse.BodyHandlers.ofString());
        log("STEP3_SECKILL_STATUS=" + srr.statusCode());
        log("STEP3_SECKILL_SUCCESS_FLAG=" + srr.body().contains("\"success\":true"));
        log("STEP3_BODY=" + ascii(srr.body()));

        // Step 4: query result after 1s
        Thread.sleep(1200);
        HttpRequest qr = HttpRequest.newBuilder()
                .uri(URI.create(base + "/seckill/result?activityId=1"))
                .header("Authorization", "Bearer " + jwt)
                .GET().timeout(java.time.Duration.ofSeconds(10)).build();
        HttpResponse<String> qrr = c.send(qr, HttpResponse.BodyHandlers.ofString());
        log("STEP4_RESULT_STATUS=" + qrr.statusCode());
        log("STEP4_BODY=" + ascii(qrr.body()));

        out.close();
    }

    private static void log(String s) {
        System.out.println(s);
        if (out != null) out.println(s);
    }

    /** 把非 ASCII 字符替换为 '?'，保证输出纯净 */
    private static String ascii(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            sb.append(ch < 128 ? ch : '?');
        }
        return sb.toString();
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
