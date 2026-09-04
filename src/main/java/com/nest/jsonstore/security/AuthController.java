package com.nest.jsonstore.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Sign-in. Credentials are exchanged once for a bearer token, which every later request carries in
 * an {@code Authorization: Bearer} header, so the API keeps no session and any replica can serve
 * any request.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenIssuer tokenIssuer;

    AuthController(AuthenticationManager authenticationManager, TokenIssuer tokenIssuer) {
        this.authenticationManager = authenticationManager;
        this.tokenIssuer = tokenIssuer;
    }

    @Operation(summary = "Exchange directory credentials for a bearer token")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in"),
            @ApiResponse(responseCode = "400", description = "Username or password missing"),
            @ApiResponse(responseCode = "401", description = "Wrong username or password")
    })
    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        return tokenResponse(tokenIssuer.issue(authentication));
    }

    /**
     * A fresh token for a caller who already has a valid one, so a long session does not have to
     * ask for the password again — up to a point: a sign-in cannot be stretched past
     * {@code app.security.jwt.max-session}, after which the password is asked for once more.
     */
    @Operation(summary = "Exchange a valid token for a new one")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A new token, with the same identity and roles"),
            @ApiResponse(responseCode = "401", description = "No valid token, or the sign-in is too old to renew")
    })
    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(JwtAuthenticationToken authentication) {
        return tokenResponse(tokenIssuer.renew(authentication));
    }

    @Operation(summary = "Who the caller is, according to the token they sent")
    @GetMapping("/me")
    AuthenticatedUser me(Authentication authentication) {
        // Reaching this method at all means the token was accepted; an anonymous caller is turned
        // away by the filter chain with a 401 long before here.
        return AuthenticatedUser.of(authentication);
    }

    /** Tokens are credentials: no cache may keep a copy (RFC 6749 §5.1). */
    private static ResponseEntity<TokenResponse> tokenResponse(TokenIssuer.IssuedToken issued) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TokenResponse.of(issued));
    }

    record LoginRequest(
            @NotBlank(message = "Username is required") String username,
            @NotBlank(message = "Password is required") String password) {
    }

    /**
     * The bearer token and what it is worth, named the way {@code Authorization: Bearer} clients
     * expect: {@code expiresIn} is seconds from now, {@code expiresAt} the same moment as a date.
     */
    record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            Instant expiresAt,
            AuthenticatedUser user) {

        static TokenResponse of(TokenIssuer.IssuedToken issued) {
            long seconds = Math.max(0, issued.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
            return new TokenResponse(issued.value(), "Bearer", seconds, issued.expiresAt(), issued.user());
        }
    }
}
