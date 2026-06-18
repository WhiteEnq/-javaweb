package org.example.demo.resource;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import org.example.demo.common.ApiException;
import org.example.demo.common.BaseResource;
import org.example.demo.common.PayConfig;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/payments/alipay")
public class AlipayPaymentResource extends BaseResource {
    private static final String FORMAT = "JSON";
    private static final String CHARSET = "UTF-8";
    private static final String SIGN_TYPE = "RSA2";

    @POST
    @Path("/start")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> createPagePay(@Context HttpHeaders headers, PayRequest request) throws Exception {
        require(request != null && request.orderId != null, "缺少订单ID");
        Map<String, Object> user = requireUser(headers, null);
        require("user".equals(user.get("role")), "管理员账号不能发起用户支付");

        Map<String, Object> order = queryOne("""
                select id, order_no, user_id, total_price, status, create_time
                from order_info
                where id = ?
                """, request.orderId);
        if (order == null) {
            throw new ApiException(404, "订单不存在");
        }
        requireSameUser(user, ((Number) order.get("user_id")).intValue());

        PayConfig.AlipaySettings settings = PayConfig.alipay();
        if (!settings.ready()) {
            throw new ApiException(400, "支付宝支付还未配置，缺少：" + String.join("、", settings.missingFields())
                    + "。请填写 src/main/resources/pay.local.properties 后重新打包部署");
        }

        String orderNo = String.valueOf(order.get("order_no"));
        BigDecimal totalAmount = ((BigDecimal) order.get("total_price")).setScale(2, RoundingMode.HALF_UP);
        int status = ((Number) order.get("status")).intValue();
        if (status > 0) {
            return ok(paymentStatusResult(request.orderId, orderNo, status, "local", "订单已支付"));
        }

        PaymentSync sync = syncPaidOrderFromAlipay(settings, orderNo, totalAmount);
        if (sync.paid()) {
            return ok(paymentStatusResult(request.orderId, orderNo, sync.status(), sync.source(), "支付宝已确认支付，订单状态已更新"));
        }

        require(queryOne("""
                select id
                from order_info
                where id = ?
                  and create_time >= date_sub(now(), interval 30 minute)
                """, request.orderId) != null, "订单支付已超时，请重新购买");

        boolean wap = request.isWap();
        String productCode = wap ? "QUICK_WAP_WAY" : "FAST_INSTANT_TRADE_PAY";
        String formHtml = buildPaymentForm(settings, wap,
                bizContent(orderNo, totalAmount, settings.subjectPrefix(), productCode,
                        wap ? settings.publicBaseUrl() : null));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", orderNo);
        result.put("clientType", wap ? "wap" : "page");
        result.put("formHtml", formHtml);
        result.put("paymentUrl", "");
        result.put("notifyUrl", settings.notifyUrl());
        result.put("returnUrl", settings.returnUrl());
        result.put("paid", false);
        result.put("status", status);
        return ok(result);
    }

    @GET
    @Path("/diagnostics")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> diagnostics() {
        PayConfig.AlipaySettings settings = PayConfig.alipay();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", settings.enabled());
        result.put("ready", settings.ready());
        result.put("missingFields", settings.missingFields());
        result.put("gateway", settings.gateway());
        result.put("appId", mask(settings.appId()));
        result.put("publicBaseUrl", settings.publicBaseUrl());
        result.put("notifyUrl", settings.notifyUrl());
        result.put("returnUrl", settings.returnUrl());
        result.put("returnPageUrl", settings.returnPageUrl());
        result.put("privateKeyPresent", settings.privateKey() != null && !settings.privateKey().isBlank());
        result.put("alipayPublicKeyPresent", settings.alipayPublicKey() != null && !settings.alipayPublicKey().isBlank());
        result.put("privateKeyLoadable", canLoadPrivateKey(settings.privateKey()));
        result.put("alipayPublicKeyLoadable", canLoadPublicKey(settings.alipayPublicKey()));
        result.put("sdkMode", true);
        result.put("warnings", diagnosticsWarnings(settings));
        return ok(result);
    }

    @POST
    @Path("/page-pay")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> createLegacyPagePay(@Context HttpHeaders headers, PayRequest request) throws Exception {
        return createPagePay(headers, request);
    }

    @POST
    @Path("/sync")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> syncPaymentStatus(@Context HttpHeaders headers, PayRequest request) throws Exception {
        require(request != null && request.orderId != null, "缺少订单ID");
        Map<String, Object> user = requireUser(headers, null);
        require("user".equals(user.get("role")), "管理员账号不能同步用户支付");

        Map<String, Object> order = queryOne("""
                select id, order_no, user_id, total_price, status
                from order_info
                where id = ?
                """, request.orderId);
        if (order == null) {
            throw new ApiException(404, "订单不存在");
        }
        requireSameUser(user, ((Number) order.get("user_id")).intValue());

        String orderNo = String.valueOf(order.get("order_no"));
        int status = ((Number) order.get("status")).intValue();
        if (status > 0) {
            return ok(paymentStatusResult(request.orderId, orderNo, status, "local", "订单已支付"));
        }

        PayConfig.AlipaySettings settings = PayConfig.alipay();
        if (!settings.ready()) {
            throw new ApiException(400, "支付宝支付还未配置，缺少：" + String.join("、", settings.missingFields()));
        }

        BigDecimal totalAmount = ((BigDecimal) order.get("total_price")).setScale(2, RoundingMode.HALF_UP);
        PaymentSync sync = syncPaidOrderFromAlipay(settings, orderNo, totalAmount);
        int currentStatus = sync.paid() ? sync.status() : 0;
        return ok(paymentStatusResult(request.orderId, orderNo, currentStatus, sync.source(),
                sync.paid() ? "支付宝已确认支付，订单状态已更新" : sync.message()));
    }

    @POST
    @Path("/notify")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    public Response notify(MultivaluedMap<String, String> form) throws Exception {
        Map<String, String> params = firstValues(form);
        PayConfig.AlipaySettings settings = PayConfig.alipay();
        if (!settings.ready()) {
            return Response.ok("fail").build();
        }
        if (!AlipaySignature.rsaCheckV1(params, settings.alipayPublicKey(), CHARSET, SIGN_TYPE)) {
            return Response.ok("fail").build();
        }
        if (!settings.appId().equals(params.get("app_id"))) {
            return Response.ok("fail").build();
        }

        String tradeStatus = params.get("trade_status");
        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            return Response.ok("success").build();
        }

        String orderNo = params.get("out_trade_no");
        BigDecimal paidAmount = parseAmount(params.get("total_amount"));
        Map<String, Object> order = queryOne("""
                select id, total_price, status
                from order_info
                where order_no = ?
                """, orderNo);
        if (order == null) {
            return Response.ok("fail").build();
        }
        BigDecimal orderAmount = ((BigDecimal) order.get("total_price")).setScale(2, RoundingMode.HALF_UP);
        if (paidAmount == null || paidAmount.compareTo(orderAmount) != 0) {
            return Response.ok("fail").build();
        }

        int status = ((Number) order.get("status")).intValue();
        if (status == 0) {
            update("update order_info set status = 1 where order_no = ? and status = 0", orderNo);
        }
        return Response.ok("success").build();
    }

    @GET
    @Path("/return")
    public Response returnToOrders(@Context UriInfo uriInfo) {
        try {
            syncReturnPayment(uriInfo);
        } catch (Exception ignored) {
            // 同步回跳不能影响用户回到订单页，最终状态仍可通过异步通知或查单接口修正。
        }
        return Response.seeOther(URI.create(PayConfig.alipay().returnPageUrl())).build();
    }

    private String buildPaymentForm(PayConfig.AlipaySettings settings, boolean wap, String bizContent)
            throws AlipayApiException {
        AlipayClient alipayClient = alipayClient(settings);
        if (wap) {
            AlipayTradeWapPayRequest payRequest = new AlipayTradeWapPayRequest();
            payRequest.setNotifyUrl(settings.notifyUrl());
            payRequest.setReturnUrl(settings.returnUrl());
            payRequest.setBizContent(bizContent);
            return alipayClient.pageExecute(payRequest).getBody();
        }

        AlipayTradePagePayRequest payRequest = new AlipayTradePagePayRequest();
        payRequest.setNotifyUrl(settings.notifyUrl());
        payRequest.setReturnUrl(settings.returnUrl());
        payRequest.setBizContent(bizContent);
        return alipayClient.pageExecute(payRequest).getBody();
    }

    private AlipayClient alipayClient(PayConfig.AlipaySettings settings) {
        return new DefaultAlipayClient(
                settings.gateway(),
                settings.appId(),
                settings.privateKey(),
                FORMAT,
                CHARSET,
                settings.alipayPublicKey(),
                SIGN_TYPE
        );
    }

    private PaymentSync syncPaidOrderFromAlipay(PayConfig.AlipaySettings settings, String orderNo,
                                                BigDecimal expectedAmount) throws Exception {
        AlipayTradeQueryRequest queryRequest = new AlipayTradeQueryRequest();
        queryRequest.setBizContent("{\"out_trade_no\":\"" + json(orderNo) + "\"}");
        AlipayTradeQueryResponse response;
        try {
            response = alipayClient(settings).execute(queryRequest);
        } catch (AlipayApiException e) {
            return new PaymentSync(false, 0, "alipay-query", "支付宝查单暂不可用");
        }
        if (response == null || !response.isSuccess()) {
            String message = response == null ? "支付宝查单无响应" : firstNonBlank(response.getSubMsg(), response.getMsg(), "支付宝暂未确认支付");
            return new PaymentSync(false, 0, "alipay-query", message);
        }
        String tradeStatus = response.getTradeStatus();
        BigDecimal paidAmount = parseAmount(response.getTotalAmount());
        if (!isPaidTradeStatus(tradeStatus)) {
            return new PaymentSync(false, 0, "alipay-query", "支付宝交易状态：" + firstNonBlank(tradeStatus, "未支付"));
        }
        if (paidAmount == null || paidAmount.compareTo(expectedAmount.setScale(2, RoundingMode.HALF_UP)) != 0) {
            return new PaymentSync(false, 0, "alipay-query", "支付宝金额与订单金额不一致");
        }
        update("update order_info set status = 1 where order_no = ? and status = 0", orderNo);
        return new PaymentSync(true, 1, "alipay-query", "支付宝已确认支付");
    }

    private void syncReturnPayment(UriInfo uriInfo) throws Exception {
        if (uriInfo == null) {
            return;
        }
        Map<String, String> params = firstValues(uriInfo.getQueryParameters());
        String orderNo = params.get("out_trade_no");
        if (orderNo == null || orderNo.isBlank()) {
            return;
        }
        PayConfig.AlipaySettings settings = PayConfig.alipay();
        if (!settings.ready() || !AlipaySignature.rsaCheckV1(params, settings.alipayPublicKey(), CHARSET, SIGN_TYPE)) {
            return;
        }
        Map<String, Object> order = queryOne("""
                select total_price
                from order_info
                where order_no = ?
                """, orderNo);
        if (order == null) {
            return;
        }
        BigDecimal expectedAmount = ((BigDecimal) order.get("total_price")).setScale(2, RoundingMode.HALF_UP);
        PaymentSync sync = syncPaidOrderFromAlipay(settings, orderNo, expectedAmount);
        if (sync.paid()) {
            return;
        }
        BigDecimal returnAmount = parseAmount(params.get("total_amount"));
        if (returnAmount != null && returnAmount.compareTo(expectedAmount) == 0 && params.get("trade_no") != null) {
            update("update order_info set status = 1 where order_no = ? and status = 0", orderNo);
        }
    }

    private Map<String, Object> paymentStatusResult(Integer orderId, String orderNo, int status,
                                                    String source, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("orderNo", orderNo);
        result.put("status", status);
        result.put("paid", status > 0);
        result.put("source", source);
        result.put("message", message);
        result.put("formHtml", "");
        result.put("paymentUrl", "");
        return result;
    }

    private boolean isPaidTradeStatus(String tradeStatus) {
        return "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String bizContent(String orderNo, BigDecimal totalAmount, String subjectPrefix, String productCode,
                              String quitUrl) {
        String content = "{"
                + "\"out_trade_no\":\"" + json(orderNo) + "\","
                + "\"product_code\":\"" + json(productCode) + "\","
                + "\"total_amount\":\"" + totalAmount.toPlainString() + "\","
                + "\"subject\":\"" + json(subjectPrefix + " " + orderNo) + "\"";
        if (quitUrl != null && !quitUrl.isBlank()) {
            content += ",\"quit_url\":\"" + json(quitUrl) + "\"";
        }
        return content + "}";
    }

    private Map<String, String> firstValues(MultivaluedMap<String, String> form) {
        Map<String, String> params = new LinkedHashMap<>();
        if (form == null) {
            return params;
        }
        for (Map.Entry<String, List<String>> entry : form.entrySet()) {
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                params.put(entry.getKey(), values.get(0));
            }
        }
        return params;
    }

    private BigDecimal parseAmount(String value) {
        try {
            return value == null ? null : new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private PrivateKey privateKey(String text) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(cleanKey(text));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private PublicKey publicKey(String text) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(cleanKey(text));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
    }

    private String cleanKey(String text) {
        return String.valueOf(text)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
    }

    private String json(String value) {
        return String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = value.trim();
        if (text.length() <= 8) {
            return "****";
        }
        return text.substring(0, 4) + "****" + text.substring(text.length() - 4);
    }

    private boolean canLoadPrivateKey(String text) {
        try {
            privateKey(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean canLoadPublicKey(String text) {
        try {
            publicKey(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> diagnosticsWarnings(PayConfig.AlipaySettings settings) {
        List<String> warnings = new ArrayList<>();
        String baseUrl = settings.publicBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return warnings;
        }
        if (!baseUrl.startsWith("https://")) {
            warnings.add("alipay.public-base-url 建议使用 https 公网地址");
        }
        if (baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")) {
            warnings.add("支付宝无法从公网访问 localhost，请使用 Cloudflare tunnel 地址");
        }
        if (!baseUrl.endsWith("/demo")) {
            warnings.add("当前项目固定按 /demo 部署，alipay.public-base-url 应以 /demo 结尾");
        }
        if (settings.gateway() != null && !settings.gateway().contains("sandbox")) {
            warnings.add("当前网关不是沙箱网关，请确认是否误填正式支付宝网关");
        }
        return warnings;
    }

    public static class PayRequest {
        public Integer orderId;
        public String clientType;

        public boolean isWap() {
            return "wap".equalsIgnoreCase(clientType) || "mobile".equalsIgnoreCase(clientType);
        }
    }

    private record PaymentSync(boolean paid, int status, String source, String message) {
    }
}
