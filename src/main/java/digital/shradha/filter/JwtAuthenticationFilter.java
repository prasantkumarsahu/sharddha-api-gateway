package digital.shradha.filter;

import digital.shradha.properties.ApiProperties;
import digital.shradha.service.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
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

		// Skip public endpoints
		if (apiProperties.getOpenEndpoints().stream().anyMatch(path :: contains)) {
			log.info("Skipping authentication for public endpoint: {}", path);
			return chain.filter(exchange);
		}

		ServerHttpRequest request = exchange.getRequest();
		ServerHttpResponse response = exchange.getResponse();

		// Extract token from cookie
		HttpCookie tokenCookie = request.getCookies().getFirst("token");
		if (tokenCookie == null) {
			log.warn("Missing token cookie → returning 401");
			response.setStatusCode(HttpStatus.UNAUTHORIZED);
			return response.setComplete();
		}

		String token = tokenCookie.getValue();

		try {
			log.debug("Validating JWT token from cookie...");
			boolean isValid = jwtService.validateToken(token);

			if (! isValid) {
				log.warn("Invalid JWT token → returning 401");
				response.setStatusCode(HttpStatus.UNAUTHORIZED);
				return response.setComplete();
			}

			String username = jwtService.extractUsernameOrEmail(token);
			log.info("Token valid. Injecting X-USER header → {}", username);

			// Forward username to downstream services
			ServerWebExchange mutatedExchange = exchange.mutate()
					.request(request.mutate()
							.header("X-USER", username)
							.build())
					.build();

			return chain.filter(mutatedExchange);

		} catch (Exception e) {
			log.error("JWT processing failed: {}", e.getMessage(), e);
			response.setStatusCode(HttpStatus.UNAUTHORIZED);
			return response.setComplete();
		}
	}

	@Override
	public int getOrder() {
		return - 1; // Execute early
	}
}
