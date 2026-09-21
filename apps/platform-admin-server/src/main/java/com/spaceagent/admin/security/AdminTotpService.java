package com.spaceagent.admin.security;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.recovery.RecoveryCodeGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.OptionalLong;

public class AdminTotpService {
    private static final long TIME_STEP_SECONDS = 30;
    private final CodeGenerator generator;
    private final SecretGenerator secretGenerator;
    private final RecoveryCodeGenerator recoveryCodeGenerator;
    private final Clock clock;

    public AdminTotpService(Clock clock) {
        this(new DefaultCodeGenerator(), new DefaultSecretGenerator(),
                new RecoveryCodeGenerator(), clock);
    }

    AdminTotpService(CodeGenerator generator, Clock clock) {
        this(generator, new DefaultSecretGenerator(), new RecoveryCodeGenerator(), clock);
    }

    AdminTotpService(CodeGenerator generator, SecretGenerator secretGenerator,
                     RecoveryCodeGenerator recoveryCodeGenerator, Clock clock) {
        this.generator = generator;
        this.secretGenerator = secretGenerator;
        this.recoveryCodeGenerator = recoveryCodeGenerator;
        this.clock = clock;
    }

    public String generateSecret() {
        return secretGenerator.generate();
    }

    public String[] generateRecoveryCodes(int count) {
        if (count < 1 || count > 20) throw new IllegalArgumentException("recovery code count is invalid");
        return recoveryCodeGenerator.generateCodes(count);
    }

    public OptionalLong matchingTimeStep(String secret, String candidate) {
        if (candidate == null || !candidate.matches("\\d{6}")) {
            return OptionalLong.empty();
        }
        long current = clock.instant().getEpochSecond() / TIME_STEP_SECONDS;
        for (long step = current - 1; step <= current + 1; step++) {
            try {
                String generated = generator.generate(secret, step);
                if (MessageDigest.isEqual(generated.getBytes(StandardCharsets.US_ASCII),
                        candidate.getBytes(StandardCharsets.US_ASCII))) {
                    return OptionalLong.of(step);
                }
            } catch (CodeGenerationException error) {
                throw new IllegalStateException("Unable to validate administrator MFA", error);
            }
        }
        return OptionalLong.empty();
    }
}
