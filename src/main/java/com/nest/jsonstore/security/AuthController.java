package com.nest.jsonstore.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenIssuer tokenIssuer;

    AuthController(AuthenticationManager authenticationManager, TokenIssuer tokenIssuer) {
        this.authenticationManager = authenticationManager;
        this.tokenIssuer = tokenIssuer;
    }

    /** Binds the credentials against LDAP and returns a bearer token for the API. */
    @PostMapping("/login")
    TokenIssuer.IssuedToken login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        return tokenIssuer.issue(authentication);
    }

    /** Who the caller is, according to the token they sent. */
    @GetMapping("/me")
    ResponseEntity<CurrentUser> me(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(401).build();
        }
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
                .sorted()
                .toList();
        return ResponseEntity.ok(new CurrentUser(authentication.getName(), roles));
    }

    record LoginRequest(
            @NotBlank(message = "Username is required") String username,
            @NotBlank(message = "Password is required") String password) {
    }

    record CurrentUser(String username, List<String> roles) {
    }
}
