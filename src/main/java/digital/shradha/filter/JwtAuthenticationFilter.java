package digital.shradha.filter;

import digital.shradha.properties.ApiProperties;
import digital.shradha.service.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtService jwtService;
    private final ApiProperties apiProperties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        String path = exchange.getRequest().getPath().toString();
        log.info("Incoming request → PATH: {}", path);

        // Public endpoints skip auth
        if (apiProperties.getOpenEndpoints().stream().anyMatch(path::contains)) {
            log.info("Skipping authentication for public endpoint: {}", path);
            return chain.filter(exchange);
        }

        // Extract header
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (authHeader == null) {
            log.warn("Missing Authorization header → returning 401");
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        log.debug("Authorization header received (masked): {}", maskToken(authHeader));

        if (!authHeader.startsWith("Bearer ")) {
            log.warn("Invalid Authorization header format → returning 401");
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // Extract token
        String token = authHeader.substring(7);

        try {
            log.debug("Validating JWT token...");
            boolean isValid = jwtService.validateToken(token);

            if (!isValid) {
                log.warn("Invalid JWT token → returning 401");
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            String username = jwtService.extractUsernameOrEmail(token);
            log.info("Token valid. Injecting X-USER header → {}", username);

            ServerWebExchange mutatedExchange = exchange.mutate()
                    .request(exchange.getRequest().mutate()
                            .header("X-USER", username)
                            .build())
                    .build();

            return chain.filter(mutatedExchange);

        } catch (Exception e) {
            log.error("JWT processing failed: {}", e.getMessage(), e);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -1;
    }

    // Utility: mask token
    private String maskToken(String header) {
        try {
            if (!header.startsWith("Bearer ")) return "INVALID_HEADER";

            String token = header.substring(7);
            if (token.length() <= 10) return "Bearer ****";

            return "Bearer " + token.substring(0, 5) + "****" + token.substring(token.length() - 5);
        } catch (Exception e) {
            return "Bearer ****";
        }
    }
}
