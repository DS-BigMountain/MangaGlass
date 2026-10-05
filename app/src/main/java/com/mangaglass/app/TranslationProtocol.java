package com.mangaglass.app;

import java.net.URI;

/** Validates the configured Chat Completions endpoint. */
public final class TranslationProtocol {
    private TranslationProtocol() {}

    public static String endpoint(String value) {
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            String url = uri.toString().replaceAll("/+$", "");
            return url.endsWith("/chat/completions") ? url : url + "/chat/completions";
        } catch (Exception e) {
            throw new IllegalArgumentException("API 地址需为 HTTPS，不能包含账号、查询参数或片段");
        }
    }

}
