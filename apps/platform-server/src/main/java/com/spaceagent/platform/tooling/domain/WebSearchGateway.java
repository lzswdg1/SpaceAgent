package com.spaceagent.platform.tooling.domain;

import java.util.List;

public interface WebSearchGateway {
    boolean available();

    SearchResult search(SearchRequest request);

    record SearchRequest(
            String query,
            int maxResults,
            String language,
            String categories,
            String timeRange) {
    }

    record SearchResult(String query, List<SearchHit> results) {
        public SearchResult {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    record SearchHit(
            String title,
            String url,
            String snippet,
            String engine,
            double score,
            String publishedAt) {
    }
}
