package local.barksms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

final class BarkClient {
    static void send(String endpoint, String title, String body, String group, boolean archive) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(Policy.endpoint(endpoint)).openConnection();
        try {
            JSONObject json = new JSONObject();
            json.put("title", title);
            json.put("body", body);
            json.put("group", Policy.groupName(group));
            json.put("isArchive", archive ? 1 : 0);
            byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Bark 返回 HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (bytes.size() + n > 16_384) throw new IOException("Bark 响应异常");
                    bytes.write(buffer, 0, n);
                }
                JSONObject response = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                if (response.optInt("code", -1) != 200) throw new IOException("Bark 未确认成功，请检查地址和 Key");
            }
        } catch (JSONException e) {
            throw new IOException("Bark 响应格式异常");
        } catch (javax.net.ssl.SSLException e) {
            throw new IOException("HTTPS 证书或连接异常");
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("网络连接超时");
        } catch (java.net.UnknownHostException e) {
            throw new IOException("无法解析 Bark 服务器地址");
        } catch (IOException e) {
            String message = e.getMessage();
            if (message != null && (message.startsWith("Bark ") || message.equals("Bark 响应异常"))) throw e;
            throw new IOException("网络连接失败");
        } finally { connection.disconnect(); }
    }
}
