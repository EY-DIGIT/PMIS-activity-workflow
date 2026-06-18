package com.pmis.activityworkflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmis.activityworkflow.config.IntrospectProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Validates every inbound request by introspecting its Bearer token against
 * the upstream users API.
 *
 * <p>Rules:
 * <ol>
 *   <li>If introspect is disabled (app.introspect.enabled=false), allow all.</li>
 *   <li>If no {@code Authorization: Bearer <token>} header is present → 401.</li>
 *   <li>Call the introspect endpoint; if {@code active=false} or {@code expired=true} → 401.</li>
 *   <li>Store the resolved {@link IntrospectResult} as request attribute
 *       {@code "introspectResult"} for downstream use.</li>
 * </ol>
 *
 * <p>Swagger UI / OpenAPI paths are excluded so documentation stays accessible.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class AuthTokenFilter extends OncePerRequestFilter {

    /** Request attribute key where the validated result is stored. */
    public static final String ATTR_INTROSPECT = "introspectResult";

    private final TokenIntrospectClient introspectClient;
    private final IntrospectProperties props;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        if (!props.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String auth = request.getHeader("Authorization");
        if (!StringUtils.hasText(auth) || !auth.startsWith("Bearer ")) {
            sendUnauthorized(response, "Missing or invalid Authorization header");
            return;
        }

        String token = auth.substring(7).trim();
        IntrospectResult result = introspectClient.introspect(token);

        if (!result.active()) {
            log.warn("Token introspection failed for request {} {}: token is inactive or expired",
                    request.getMethod(), request.getRequestURI());
            sendUnauthorized(response, "Token is invalid or expired");
            return;
        }

        log.debug("Authenticated userId={} for {} {}", result.userId(),
                request.getMethod(), request.getRequestURI());
        request.setAttribute(ATTR_INTROSPECT, result);
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.equals("/actuator/health")
                || path.startsWith("/actuator/");
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                Map.of("status", 401, "error", "Unauthorized", "message", message));
    }
}
