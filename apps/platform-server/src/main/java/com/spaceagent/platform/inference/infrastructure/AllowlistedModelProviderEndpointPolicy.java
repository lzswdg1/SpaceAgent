package com.spaceagent.platform.inference.infrastructure;

import com.spaceagent.platform.inference.domain.ModelProviderEndpointPolicy;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/**
 * Prevents persisted model-provider endpoints from becoming an unrestricted SSRF primitive.
 */
@Component
public class AllowlistedModelProviderEndpointPolicy implements ModelProviderEndpointPolicy {

    private final InferenceProperties properties;

    public AllowlistedModelProviderEndpointPolicy(InferenceProperties properties) {
        this.properties = properties;
    }

    @Override
    public String validateAndNormalize(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("Model provider Base URL is required");
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = lower(uri.getScheme());
            String host = lower(uri.getHost());
            boolean localHost = isLocalHost(host);
            boolean permittedScheme = "https".equals(scheme)
                    || ("http".equals(scheme) && localHost && properties.isAllowLocalProviderHosts());
            if (!permittedScheme) {
                throw invalid("Model provider Base URL must use HTTPS");
            }
            if (host.isBlank() || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw invalid("Invalid model provider Base URL");
            }
            boolean allowed = (localHost && properties.isAllowLocalProviderHosts())
                    || properties.getAllowedProviderHosts().stream()
                    .filter(configured -> configured != null && !configured.isBlank())
                    .map(configured -> lower(configured.trim()))
                    .anyMatch(configured -> host.equals(configured)
                            || (configured.startsWith("*.")
                            && host.endsWith(configured.substring(1))));
            if (!allowed) {
                throw invalid("Model provider host is not allowed: " + host);
            }
            return value.trim().replaceAll("/+$", "");
        } catch (BusinessException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid model provider Base URL");
        }
    }

    private static boolean isLocalHost(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "MODEL_PROVIDER_ENDPOINT_REJECTED");
    }
}
