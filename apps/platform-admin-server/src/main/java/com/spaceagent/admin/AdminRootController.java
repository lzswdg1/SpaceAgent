package com.spaceagent.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AdminRootController {
    @GetMapping("/")
    Map<String, String> root() {
        return Map.of("service", "platform-admin-server", "status", "running");
    }
}
