package local.barksms;

import java.net.URI;
import java.net.URISyntaxException;

public final class Policy {
    private Policy() {}

    public static String groupName(String input) {
        String value = input == null ? "" : input.trim();
        return value.isEmpty() ? "短信" : value;
    }

    public static String endpoint(String input) {
        String value = input == null ? "" : input.trim();
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0) {
                throw new IllegalArgumentException("请填写 HTTPS 推送地址，不要包含参数或推送正文");
            }
            String path = uri.getRawPath();
            if (path == null || path.replace("/", "").isEmpty()) {
                throw new IllegalArgumentException("推送地址缺少设备 Key");
            }
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            if ("api.day.app".equalsIgnoreCase(uri.getHost())
                    && value.substring(value.indexOf('/', 8) + 1).contains("/")) {
                throw new IllegalArgumentException("请只保留 https://api.day.app/设备Key");
            }
            return value;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("推送地址格式不正确");
        }
    }

    public static long retryDelay(int failures) {
        int exponent = Math.max(0, Math.min(5, failures - 1));
        return Math.min(15 * 60_000L, 30_000L << exponent);
    }
}
