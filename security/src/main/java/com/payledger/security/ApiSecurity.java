package com.payledger.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The settings every API shares: stateless bearer-token authentication, no CSRF/session/form login, and JSON
 * 401/403 answers that never repeat exception text. Services add their own URL rules on top.
 */
public class ApiSecurity {

    static final String UNAUTHENTICATED = "a valid bearer token is required";
    static final String FORBIDDEN = "you are not allowed to do this";

    private final JwtAuthenticationConverter converter;
    private final AuthenticationEntryPoint entryPoint = (request, response, ex) -> {
        response.setHeader("WWW-Authenticate", "Bearer");
        ProblemResponses.write(response, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED);
    };
    private final AccessDeniedHandler deniedHandler = (request, response, ex) ->
            ProblemResponses.write(response, HttpStatus.FORBIDDEN, FORBIDDEN);

    public ApiSecurity(JwtAuthenticationConverter converter) {
        this.converter = converter;
    }

    public HttpSecurity apply(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler));
    }
}
