package com.spaceagent.shared.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UuidGeneratorTest {

    @Test
    void nextId_returnsNonNull() {
        UuidGenerator generator = new UuidGenerator();
        assertNotNull(generator.nextId());
    }

    @Test
    void nextId_returnsUniqueIds() {
        UuidGenerator generator = new UuidGenerator();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(ids.add(generator.nextId()), "Duplicate ID generated at iteration " + i);
        }
    }

    @Test
    void nextId_returnsValidUuidFormat() {
        UuidGenerator generator = new UuidGenerator();
        String id = generator.nextId();
        assertTrue(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }
}
