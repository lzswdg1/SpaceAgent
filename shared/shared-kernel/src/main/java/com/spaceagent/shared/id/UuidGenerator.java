package com.spaceagent.shared.id;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UuidGenerator implements IdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}
