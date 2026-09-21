package com.spaceagent.platform.agent.application;

import com.spaceagent.platform.agent.api.AgentApiKeyApplicationApi;
import com.spaceagent.platform.agent.api.AgentApiKeyView;
import com.spaceagent.platform.agent.api.CreateAgentApiKeyCommand;
import com.spaceagent.platform.agent.api.CreatedAgentApiKeyView;
import com.spaceagent.platform.agent.api.RevokeAgentApiKeyCommand;
import com.spaceagent.platform.agent.api.VerifiedAgentApiKeyView;
import com.spaceagent.platform.agent.domain.AgentApiKey;
import com.spaceagent.platform.agent.domain.AgentApiKeyRepository;
import com.spaceagent.platform.agent.domain.AgentDefinition;
import com.spaceagent.platform.agent.domain.AgentRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class AgentApiKeyApplicationService implements AgentApiKeyApplicationApi {

    private static final String RAW_KEY_PREFIX = "agk_";
    private static final int RANDOM_BYTES = 32;

    private final AgentApiKeyRepository apiKeyRepository;
    private final AgentRepository agentRepository;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public AgentApiKeyApplicationService(
            AgentApiKeyRepository apiKeyRepository,
            AgentRepository agentRepository,
            IdGenerator idGenerator,
            TimeProvider timeProvider) {
        this.apiKeyRepository = apiKeyRepository;
        this.agentRepository = agentRepository;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
    }

    @Override
    public CreatedAgentApiKeyView create(CreateAgentApiKeyCommand command) {
        requireOwnedAgent(command.tenantId(), command.ownerId(), command.agentId());
        String name = command.name().trim();
        if (name.length() > 128) {
            throw new BusinessException("Agent API key name is too long", HttpStatus.BAD_REQUEST);
        }
        if (apiKeyRepository.findByAgentId(command.agentId()).stream()
                .anyMatch(existing -> name.equals(existing.name()))) {
            throw new BusinessException(
                    "Agent API key name already exists",
                    HttpStatus.CONFLICT,
                    "AGENT_API_KEY_NAME_CONFLICT");
        }
        Instant now = timeProvider.now();
        if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
            throw new BusinessException("Agent API key expiry must be in the future", HttpStatus.BAD_REQUEST);
        }
        String rawKey = generateRawKey();
        AgentApiKey apiKey = new AgentApiKey(
                idGenerator.nextId(),
                command.agentId(),
                name,
                hash(rawKey),
                rawKey.substring(0, Math.min(12, rawKey.length())),
                command.scopes(),
                true,
                now,
                null,
                command.expiresAt(),
                null);
        try {
            apiKeyRepository.save(apiKey);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    "Agent API key name already exists",
                    HttpStatus.CONFLICT,
                    "AGENT_API_KEY_NAME_CONFLICT");
        }
        return new CreatedAgentApiKeyView(rawKey, toView(apiKey));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentApiKeyView> list(String tenantId, String ownerId, String agentId) {
        requireOwnedAgent(tenantId, ownerId, agentId);
        return apiKeyRepository.findByAgentId(agentId).stream()
                .map(AgentApiKeyApplicationService::toView)
                .toList();
    }

    @Override
    public void revoke(RevokeAgentApiKeyCommand command) {
        requireOwnedAgent(command.tenantId(), command.ownerId(), command.agentId());
        if (!apiKeyRepository.revoke(command.agentId(), command.keyId(), timeProvider.now())) {
            throw new BusinessException("Agent API key not found", HttpStatus.NOT_FOUND);
        }
    }

    @Override
    public Optional<VerifiedAgentApiKeyView> verify(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(RAW_KEY_PREFIX)) {
            return Optional.empty();
        }
        AgentApiKey apiKey = apiKeyRepository.findByHash(hash(rawKey)).orElse(null);
        Instant now = timeProvider.now();
        if (apiKey == null || !apiKey.isValid(now)) {
            return Optional.empty();
        }
        AgentDefinition definition = agentRepository.findById(apiKey.agentId()).orElse(null);
        if (definition == null) {
            return Optional.empty();
        }
        apiKeyRepository.markUsed(apiKey.id(), now);
        return Optional.of(new VerifiedAgentApiKeyView(
                apiKey.id(),
                apiKey.agentId(),
                definition.ownerId(),
                definition.tenantId(),
                apiKey.scopes(),
                apiKey.expiresAt()));
    }

    private AgentDefinition requireOwnedAgent(String tenantId, String ownerId, String agentId) {
        return agentRepository.findById(agentId)
                .filter(definition -> tenantId.equals(definition.tenantId()))
                .filter(definition -> ownerId.equals(definition.ownerId()))
                .orElseThrow(() -> new BusinessException("Agent not found", HttpStatus.NOT_FOUND));
    }

    private String generateRawKey() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return RAW_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash Agent API key", exception);
        }
    }

    private static AgentApiKeyView toView(AgentApiKey key) {
        return new AgentApiKeyView(
                key.id(), key.agentId(), key.name(), key.keyPrefix(), key.scopes(), key.enabled(),
                key.createdAt(), key.lastUsedAt(), key.expiresAt(), key.revokedAt());
    }
}
