package com.ratelimiter.demo.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/demo")
public class DemoController {

    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping(HttpServletRequest request) {
        log.debug("Ping received from {}", request.getRemoteAddr());
        return ResponseEntity.ok(buildResponse("pong", request, null));
    }

    @GetMapping("/echo")
    public ResponseEntity<Map<String, Object>> echo(
            @RequestParam(required = false) String message,
            HttpServletRequest request) {
        return ResponseEntity.ok(buildResponse("echo", request, message));
    }

    @PostMapping("/echo")
    public ResponseEntity<Map<String, Object>> echoPost(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Map<String, Object> response = buildResponse("echo-post", request, null);
        response.put("body", body);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/slow")
    public ResponseEntity<Map<String, Object>> slow(
            @RequestParam(defaultValue = "500") long delayMs,
            HttpServletRequest request) throws InterruptedException {
        long clamped = Math.min(delayMs, 5000); // max 5 seconds
        Thread.sleep(clamped);
        Map<String, Object> response = buildResponse("slow", request, null);
        response.put("delayMs", clamped);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/headers")
    public ResponseEntity<Map<String, Object>> headers(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name, request.getHeader(name));
        }
        Map<String, Object> response = buildResponse("headers", request, null);
        response.put("incomingHeaders", headers);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info(HttpServletRequest request) {
        Map<String, Object> response = buildResponse("info", request, null);
        response.put("tenantId", request.getHeader("X-Tenant-Id"));
        response.put("userId", request.getHeader("X-User-Id"));
        response.put("userTier", request.getHeader("X-User-Tier"));
        response.put("userRoles", request.getHeader("X-User-Roles"));
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> buildResponse(String type, HttpServletRequest request, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", type);
        response.put("service", "demo-service");
        response.put("timestamp", Instant.now().toString());
        response.put("requestId", UUID.randomUUID().toString());
        response.put("remoteAddress", request.getRemoteAddr());
        if (message != null) {
            response.put("message", message);
        }
        return response;
    }
}
