package com.spaceagent.platform.tooling.domain;

public interface HttpFetchGateway {
    FetchResult fetch(String url, int maxCharacters);

    record FetchResult(
            String url,
            int status,
            String contentType,
            String title,
            String content,
            boolean truncated) {
    }
}
