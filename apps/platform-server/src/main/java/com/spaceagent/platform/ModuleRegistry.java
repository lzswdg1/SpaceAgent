package com.spaceagent.platform;

import java.util.List;

/**
 * Declares the logical modules owned by the platform-server monolith.
 *
 * <p>Every business module exposes {@code api}, {@code application}, {@code domain}, and
 * {@code infrastructure} package boundaries. The {@code shared} module exposes only
 * {@code api}, {@code domain}, and {@code infrastructure} because it is a generic kernel
 * rather than a business module with use-case logic. This registry is the source of truth
 * for architecture tests until the corresponding module contracts are implemented in M3.
 */
public final class ModuleRegistry {

    public static final List<String> MODULE_NAMES = List.of(
            "identity",
            "agent",
            "project",
            "conversation",
            "memory",
            "knowledge",
            "context",
            "runtime",
            "inference",
            "tooling",
            "artifact",
            "automation",
            "integration",
            "governance",
            "observability",
            "shared"
    );

    public static final List<String> BUSINESS_MODULE_NAMES = MODULE_NAMES.stream()
            .filter(module -> !"shared".equals(module))
            .toList();

    private ModuleRegistry() {
    }
}
