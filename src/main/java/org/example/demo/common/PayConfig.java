package org.example.demo.common;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class PayConfig {
    private static final Properties PROPERTIES = new Properties();

    static {
        try {
            loadProperties("pay.properties");
            loadProperties("pay.local.properties");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private PayConfig() {
    }

    private static void loadProperties(String resourceName) throws Exception {
        try (InputStream input = PayConfig.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input != null) {
                PROPERTIES.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        }
    }

    public static AlipaySettings alipay() {
        String publicBaseUrl = trimTrailingSlash(config("YZMM_PUBLIC_BASE_URL", "alipay.public-base-url"));
        return new AlipaySettings(
                Boolean.parseBoolean(config("YZMM_ALIPAY_ENABLED", "alipay.enabled", "false")),
                config("YZMM_ALIPAY_GATEWAY", "alipay.gateway", "https://openapi-sandbox.dl.alipaydev.com/gateway.do"),
                config("YZMM_ALIPAY_APP_ID", "alipay.app-id", ""),
                config("YZMM_ALIPAY_PRIVATE_KEY", "alipay.private-key", ""),
                config("YZMM_ALIPAY_PUBLIC_KEY", "alipay.alipay-public-key", ""),
                publicBaseUrl,
                config("YZMM_ALIPAY_SUBJECT_PREFIX", "alipay.subject-prefix", "YZMM Mall Order")
        );
    }

    private static String config(String envName, String propertyName) {
        return config(envName, propertyName, "");
    }

    private static String config(String envName, String propertyName, String defaultValue) {
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) {
            value = System.getProperty(propertyName);
        }
        if (value == null || value.isBlank()) {
            value = PROPERTIES.getProperty(propertyName);
        }
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public record AlipaySettings(
            boolean enabled,
            String gateway,
            String appId,
            String privateKey,
            String alipayPublicKey,
            String publicBaseUrl,
            String subjectPrefix
    ) {
        public boolean ready() {
            return missingFields().isEmpty();
        }

        public List<String> missingFields() {
            List<String> missing = new ArrayList<>();
            if (!enabled) {
                missing.add("alipay.enabled=true");
            }
            if (appId == null || appId.isBlank()) {
                missing.add("alipay.app-id");
            }
            if (privateKey == null || privateKey.isBlank()) {
                missing.add("alipay.private-key");
            }
            if (alipayPublicKey == null || alipayPublicKey.isBlank()) {
                missing.add("alipay.alipay-public-key");
            }
            if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
                missing.add("alipay.public-base-url");
            }
            return missing;
        }

        public String notifyUrl() {
            return publicBaseUrl + "/api/payments/alipay/notify";
        }

        public String returnUrl() {
            return publicBaseUrl + "/api/payments/alipay/return";
        }

        public String returnPageUrl() {
            return publicBaseUrl + "/#orders";
        }
    }
}
