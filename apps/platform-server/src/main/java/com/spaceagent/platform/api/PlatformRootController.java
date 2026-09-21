package com.spaceagent.platform.api;

import com.spaceagent.platform.ModuleRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/")
public class PlatformRootController {

    @GetMapping
    public Map<String, Object> root() {
        return Map.of(
                "service", "platform-server",
                "status", "ok",
                "modules", List.copyOf(ModuleRegistry.MODULE_NAMES)
        );
    }
}
