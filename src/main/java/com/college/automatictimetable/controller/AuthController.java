package com.college.automatictimetable.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles lightweight session-based authentication for the Admin and User portals.
 *
 * <p>PINs are read from environment variables at startup so that nothing sensitive
 * is stored in source code. Defaults are provided for local development only.</p>
 *
 * <ul>
 *   <li>Admin PIN — env {@code ADMIN_PIN}, default {@code admin123}</li>
 *   <li>User PIN  — env {@code USER_PIN},  default {@code user123}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Session attribute key that stores the authenticated role. */
    private static final String SESSION_ROLE = "cp_role";
    private static final String SESSION_NAME = "cp_name";

    private final String adminPin;
    private final String userPin;

    public AuthController() {
        String envAdmin = System.getenv("ADMIN_PIN");
        String envUser  = System.getenv("USER_PIN");
        this.adminPin = (envAdmin != null && !envAdmin.isBlank()) ? envAdmin : "admin123";
        this.userPin  = (envUser  != null && !envUser.isBlank())  ? envUser  : "user123";
    }

    // ── POST /api/auth/login ──────────────────────────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> body,
            HttpSession session) {

        String role = body.getOrDefault("role", "").trim().toLowerCase();
        String pin  = body.getOrDefault("pin",  "").trim();

        boolean valid = switch (role) {
            case "admin" -> adminPin.equals(pin);
            case "user"  -> userPin.equals(pin);
            default      -> false;
        };

        if (!valid) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("message", "Incorrect PIN. Please try again.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
        }

        String displayName = "admin".equals(role) ? "Administrator" : "Staff User";
        session.setAttribute(SESSION_ROLE, role);
        session.setAttribute(SESSION_NAME, displayName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", role);
        result.put("name", displayName);
        return ResponseEntity.ok(result);
    }

    // ── POST /api/auth/logout ─────────────────────────────────────────────────

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpSession session) {
        session.invalidate();
    }

    // ── GET /api/auth/me — current session info ───────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        Object role = session.getAttribute(SESSION_ROLE);
        if (role == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Not authenticated."));
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("role", role);
        info.put("name", session.getAttribute(SESSION_NAME));
        return ResponseEntity.ok(info);
    }
}
