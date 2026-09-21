package com.spaceagent.platform.tooling.api;

public interface SecureUrlFetchApplicationApi {
    FetchResult fetch(FetchCommand command);

    record FetchCommand(
            String normalizedUrl,
            String etag,
            String lastModified,
            int maximumRedirects,
            int maximumBytes) {
    }

    record FetchResult(
            String finalUrl,
            int status,
            String mediaType,
            String charset,
            String etag,
            String lastModified,
            byte[] body,
            int redirectCount) {
        public FetchResult {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
