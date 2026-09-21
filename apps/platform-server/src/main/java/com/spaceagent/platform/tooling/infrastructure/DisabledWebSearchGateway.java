package com.spaceagent.platform.tooling.infrastructure;

import com.spaceagent.platform.tooling.domain.WebSearchGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "platform.tooling.web-search",
        name = "mode",
        havingValue = "none",
        matchIfMissing = true)
public class DisabledWebSearchGateway implements WebSearchGateway {
    @Override
    public boolean available() {
        return false;
    }

    @Override
    public SearchResult search(SearchRequest request) {
        throw new IllegalStateException("Web Search provider is not configured");
    }
}
