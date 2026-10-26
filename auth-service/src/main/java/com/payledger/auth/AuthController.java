package com.payledger.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.payledger.security.Caller;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthController {

    /** Spring logs decoded request bodies at DEBUG using toString, so the password must never be part of it. */
    record LoginRequest(String username, String password) {
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=***]";
        }
    }

    /** Response bodies are logged at DEBUG too (toString), so the token itself must not be part of it. */
    record TokenResponse(String accessToken, String tokenType, long expiresIn) {
        @Override
        public String toString() {
            return "TokenResponse[accessToken=***, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
        }
    }

    record CreateUserRequest(String username, String password, String role) {
        @Override
        public String toString() {
            return "CreateUserRequest[username=" + username + ", password=***, role=" + role + "]";
        }
    }

    record UserResponse(String username, String role) {
    }

    private final UserService users;
    private final TokenService tokens;
    private final RSAKey signingKey;

    AuthController(UserService users, TokenService tokens, RSAKey signingKey) {
        this.users = users;
        this.tokens = tokens;
        this.signingKey = signingKey;
    }

    /** 200 with a token, or 401 with one fixed message whether the user is unknown or the password is wrong. */
    @PostMapping("/auth/token")
    ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request.username() == null || request.password() == null) {
            return unauthorized();
        }
        return users.authenticate(request.username(), request.password())
                .<ResponseEntity<?>>map(user -> {
                    TokenService.Issued issued = tokens.issue(user.username(), user.role());
                    return ResponseEntity.ok(new TokenResponse(issued.accessToken(), "Bearer", issued.expiresInSeconds()));
                })
                .orElseGet(AuthController::unauthorized);
    }

    /** ADMIN only (URL rule). The response never contains the password. */
    @PostMapping("/auth/users")
    ResponseEntity<UserResponse> createUser(@RequestBody CreateUserRequest request, Authentication authentication) {
        users.create(Caller.from(authentication).subject(), request.username(), request.password(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(new UserResponse(request.username(), request.role()));
    }

    /** The public half of the signing key, so other services can verify tokens without being able to forge them. */
    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("title", "Unauthorized", "status", 401, "detail", "invalid username or password"));
    }
}
