package com.spaceagent.platform.tooling.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Code-local registry. Remote MCP metadata can never register or select a verifier. */
public final class LocalToolEffectVerifierRegistry {
    private final Map<String, ToolEffectVerifier> verifiers;

    public LocalToolEffectVerifierRegistry(Collection<ToolEffectVerifier> values) {
        Map<String, ToolEffectVerifier> registered = new LinkedHashMap<>();
        for (ToolEffectVerifier verifier : values == null ? List.<ToolEffectVerifier>of() : values) {
            ToolEffectVerifier.Definition definition = verifier.definition();
            if (definition.trust() != ToolEffectVerifier.Trust.LOCAL_REVIEWED
                    || !definition.readOnly()) {
                throw new IllegalArgumentException(
                        "only read-only LOCAL_REVIEWED verifiers may be registered");
            }
            if (registered.putIfAbsent(definition.registryKey(), verifier) != null) {
                throw new IllegalArgumentException("duplicate verifier effect/tool binding");
            }
        }
        this.verifiers = Map.copyOf(registered);
    }

    public List<ToolEffectVerifier.Definition> definitions() {
        return verifiers.values().stream().map(ToolEffectVerifier::definition)
                .sorted(Comparator.comparing(ToolEffectVerifier.Definition::registryKey))
                .toList();
    }

    public Optional<ToolEffectVerifier> resolve(
            ToolEffectVerifier.EffectKind effectKind,
            String platformToolName,
            String effectSelector) {
        if (effectKind == null || platformToolName == null || effectSelector == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(verifiers.get(
                effectKind.name() + ":" + platformToolName + ":" + effectSelector));
    }

    public ToolEffectVerifier.Evidence verify(ToolEffectVerifier.Request request) {
        ToolEffectVerifier verifier = resolve(
                request.scope().effectKind(), request.platformToolName(),
                request.scope().selector())
                .orElseThrow(() -> new IllegalArgumentException(
                        "no locally reviewed verifier for effect/tool"));
        ToolEffectVerifier.Definition definition = verifier.definition();
        request.validateFor(definition);
        ToolEffectVerifier.Evidence evidence = verifier.verify(request);
        if (evidence == null) throw new IllegalArgumentException("verifier returned no evidence");
        evidence.validateFor(definition, request);
        return evidence;
    }
}
