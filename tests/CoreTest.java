package local.barksms;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class CoreTest {
    private static int checks;
    private static FakeConnection last;
    private static int status = 200;
    private static String response = "{\"code\":200,\"message\":\"success\"}";
    private static IOException failure;

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                last = new FakeConnection(url);
                return last;
            }
        } : null);
        equal("https://api.day.app/abc", Policy.endpoint(" https://api.day.app/abc/ "));
        equal("https://push.example.org:8443/bark/key", Policy.endpoint("https://push.example.org:8443/bark/key"));
        for (String invalid : new String[]{"", "http://api.day.app/key", "https://api.day.app/", "https://api.day.app/key/body",
                "https://api.day.app/key?body=test", "https://api.day.app/key#fragment", "https://user:pass@api.day.app/key",
                "https://api.day.app/a b", "https:///key"}) {
            try { Policy.endpoint(invalid); throw new AssertionError("Accepted invalid endpoint"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        equal(30_000L, Policy.retryDelay(1));
        equal(60_000L, Policy.retryDelay(2));
        equal(900_000L, Policy.retryDelay(100));
        equal("短信", Policy.groupName(null));
        equal("短信", Policy.groupName("   "));
        equal("小米验证码", Policy.groupName(" 小米验证码 "));
        String body = "验证码 123456\n包含引号\"与反斜杠\\和表情\uD83D\uDD10";
        String group = "小米\"验证码\\\uD83D\uDD10";
        BarkClient.send("https://api.day.app/private-key", "短信 · 106123", body, group, false);
        JSONObject payload = new JSONObject(new String(last.bytes.toByteArray(), StandardCharsets.UTF_8));
        equal(body, payload.getString("body"));
        equal("短信 · 106123", payload.getString("title"));
        equal(0, payload.getInt("isArchive"));
        equal(group, payload.getString("group"));
        equal("POST", last.getRequestMethod());
        equal(false, last.getInstanceFollowRedirects());
        equal(6000, last.getConnectTimeout());
        equal(8000, last.getReadTimeout());
        equal(true, last.disconnected);

        BarkClient.send("https://api.day.app/private-key", "test", "body", "另一张卡", true);
        JSONObject changed = new JSONObject(new String(last.bytes.toByteArray(), StandardCharsets.UTF_8));
        equal("另一张卡", changed.getString("group"));
        equal(1, changed.getInt("isArchive"));
        BarkClient.send("https://api.day.app/private-key", "test", "body", " ", false);
        JSONObject defaults = new JSONObject(new String(last.bytes.toByteArray(), StandardCharsets.UTF_8));
        equal("短信", defaults.getString("group"));
        equal(0, defaults.getInt("isArchive"));

        status = 302;
        expectFailure("HTTP 302");
        status = 500;
        expectFailure("HTTP 500");
        status = 200;
        response = "{\"code\":400,\"message\":\"secret private-key\"}";
        expectFailure("Bark 未确认成功");
        response = "not json: private-key";
        expectFailure("Bark 响应格式异常");
        response = "{\"message\":\"success\"}";
        expectFailure("Bark 未确认成功");
        response = new String(new char[17_000]).replace('\0', 'a');
        expectFailure("Bark 响应异常");
        failure = new java.net.SocketTimeoutException("private-key");
        expectFailure("网络连接超时");
        failure = new javax.net.ssl.SSLHandshakeException("private-key");
        expectFailure("HTTPS 证书或连接异常");
        failure = new IOException("https://api.day.app/private-key");
        expectFailure("网络连接失败");
        System.out.println("PASS: " + checks + " checks");
    }

    private static void expectFailure(String contains) throws Exception {
        try { BarkClient.send("https://api.day.app/private-key", "test", "body", "短信", false); throw new AssertionError("Expected failure"); }
        catch (IOException expected) {
            if (!expected.getMessage().contains(contains) || expected.getMessage().contains("private-key")) {
                throw new AssertionError("Unexpected or unsafe error: " + expected.getMessage());
            }
            equal(true, last.disconnected);
        }
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
        checks++;
    }

    private static final class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean disconnected;
        FakeConnection(URL url) { super(url); }
        @Override public OutputStream getOutputStream() throws IOException {
            if (failure != null) throw failure;
            return bytes;
        }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
