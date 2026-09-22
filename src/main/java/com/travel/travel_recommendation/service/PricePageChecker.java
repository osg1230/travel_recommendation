package com.travel.travel_recommendation.service;

import java.io.InputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * AI가 알려 준 가격 출처 페이지를 서버가 직접 열어, 그 페이지에 상호와 가격 숫자가 실제로 적혀 있는지 확인한다.
 * AI 응답을 그대로 믿지 않기 위한 확인이며, 확인하지 못하면 가격을 '직접 확인 필요'로 표시한다.
 * AI가 준 주소로 서버가 요청을 보내므로 https 공개 주소만 열고, 내부망·로컬 주소와 다른 곳으로의 이동(리다이렉트)은 따라가지 않는다.
 */
public class PricePageChecker {
    private static final int MAX_BYTES = 2_000_000;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final boolean allowLocalHosts;

    public PricePageChecker() { this(false); }
    // 테스트에서만 로컬 응답 서버를 열 수 있게 한다.
    PricePageChecker(boolean allowLocalHosts) { this.allowLocalHosts = allowLocalHosts; }

    /**
     * 페이지 글자(태그·공백 제거)에 상호, 주소의 도로명, 가격(최저 또는 최고, 예: 11,000 / 11000)이 모두 있으면 true다.
     * 도로명까지 보는 것은 '교동짬뽕'처럼 상호가 다른 가게의 메뉴 이름으로도 쓰일 때 다른 가게 페이지를 걸러 내기 위해서다.
     */
    public boolean confirms(String url, String placeName, String address, Integer minWon, Integer maxWon) {
        String road = roadName(address);
        if (road == null) return false;
        try {
            URI uri = URI.create(url);
            if (!allowed(uri)) return false;
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6))
                .header("User-Agent", "Mozilla/5.0 (travel-recommendation price check)").GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) return false;
                String text = pageText(new String(body.readNBytes(MAX_BYTES), StandardCharsets.UTF_8));
                return text.contains(compact(placeName)) && text.contains(road) && pricesIn(text, minWon, maxWon);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return false;
        } catch (Exception e) {
            // 열리지 않거나 읽을 수 없는 페이지는 '확인하지 못함'으로 둔다.
            return false;
        }
    }

    // https·기본 포트·공개 IP 주소만 허용한다. 호스트 이름이 내부 주소로 풀리는 경우도 막는다.
    private boolean allowed(URI uri) throws UnknownHostException {
        if (allowLocalHosts) return uri.getHost() != null;
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) return false;
            byte[] raw = address.getAddress();
            // IPv6 사설 주소(fc00::/7)와 100.64.0.0/10(통신사 내부망)도 막는다.
            if (raw.length == 16 && (raw[0] & 0xfe) == 0xfc) return false;
            if (raw.length == 4 && (raw[0] & 0xff) == 100 && (raw[1] & 0xc0) == 64) return false;
        }
        return true;
    }

    static String pageText(String html) {
        String withoutScripts = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        return compact(withoutScripts.replaceAll("<[^>]+>", " ").replace("&nbsp;", " "));
    }

    /** 주소에서 도로명(…로·…길)을 꺼낸다. 예) "강원특별자치도 강릉시 초당순두부길 47" → "초당순두부길". 없으면 null이다. */
    static String roadName(String address) {
        if (address == null) return null;
        for (String part : address.strip().split("\\s+"))
            if (part.length() >= 2 && (part.endsWith("로") || part.endsWith("길"))) return part;
        return null;
    }

    static String compact(String text) { return text == null ? "" : text.replaceAll("\\s+", ""); }

    static boolean pricesIn(String text, Integer minWon, Integer maxWon) {
        for (Integer won : Arrays.asList(minWon, maxWon)) {
            if (won == null) continue;
            if (text.contains(String.format(Locale.ROOT, "%,d", won)) || text.contains(Integer.toString(won))) return true;
        }
        return false;
    }
}
