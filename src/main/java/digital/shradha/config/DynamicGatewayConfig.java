package digital.shradha.config;

import digital.shradha.util.ApiEndPointsUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Configuration
@EnableScheduling
public class DynamicGatewayConfig {

    private final Set<String> registeredRoutes = ConcurrentHashMap.newKeySet();
    private DiscoveryClient discoveryClient;
    private RouteDefinitionWriter routeDefinitionWriter;
    private ApplicationEventPublisher eventPublisher;
    private volatile boolean initialized = false;

    /**
     * Use setter injection to avoid circular dependency with RouteLocator
     */
    @Autowired
    public void setDiscoveryClient(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    @Autowired
    public void setRouteDefinitionWriter(RouteDefinitionWriter routeDefinitionWriter) {
        this.routeDefinitionWriter = routeDefinitionWriter;
    }

    @Autowired
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Initialize routes after application context is fully loaded
     * Delayed to ensure all beans are ready
     */
    @PostConstruct
    public void initializeRoutes() {
        log.info("Scheduling dynamic routes initialization...");
        // Delay initialization to avoid circular dependency
        new Thread(() -> {
            try {
                Thread.sleep(2000); // Wait 2 seconds for context to fully initialize
                refreshRoutes();
                initialized = true;
                log.info("Dynamic routes initialization completed");
            } catch (InterruptedException e) {
                log.error("Route initialization interrupted", e);
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    /**
     * Listen to Eureka heartbeat events to detect service changes.
     * Only refreshes when the service list actually changes.
     */
    @EventListener(HeartbeatEvent.class)
    public void onHeartbeat(HeartbeatEvent event) {
        if (!initialized) {
            return; // Skip until initialization completes
        }

        List<String> currentServices = discoveryClient.getServices().stream()
                .filter(s -> Arrays.stream(ApiEndPointsUtil.getNotRegisterServices()).noneMatch(s::equalsIgnoreCase))
                .toList();
        Set<String> currentServiceSet = new HashSet<>(currentServices);

        // Only refresh if service list changed
        if (!registeredRoutes.equals(currentServiceSet)) {
            log.info("Service list changed. Current: {}, Registered: {}",
                    currentServiceSet, registeredRoutes);
            refreshRoutes();
        }
    }

//    /**
//     * Fallback: Periodically check for route changes every 30 seconds
//     * This ensures routes are updated even if heartbeat events are missed
//     */
//    @Scheduled(fixedDelay = 30000, initialDelay = 5000)
//    public void scheduledRouteRefresh() {
//        if (!initialized) {
//            return;
//        }
//
//        List<String> currentServices = discoveryClient.getServices();
//        Set<String> currentServiceSet = new HashSet<>(currentServices);
//
//        if (!registeredRoutes.equals(currentServiceSet)) {
//            log.debug("Scheduled check: Service list changed");
//            refreshRoutes();
//        }
//    }

    /**
     * Synchronizes Gateway routes with services registered in Eureka.
     * - Adds routes for new services
     * - Updates routes for existing services
     * - Removes routes for deregistered services
     */
    private void refreshRoutes() {
        List<String> currentServices = discoveryClient.getServices().stream()
                .filter(s -> Arrays.stream(ApiEndPointsUtil.getNotRegisterServices()).noneMatch(s::equalsIgnoreCase))
                .toList();
        Set<String> currentServiceSet = new HashSet<>(currentServices);

        log.info("Refreshing routes. Services in Eureka: {}", currentServiceSet);

        // Find services that need to be removed (in registeredRoutes but not in Eureka)
        Set<String> servicesToRemove = new HashSet<>(registeredRoutes);
        servicesToRemove.removeAll(currentServiceSet);

        // Remove stale routes
        Flux.fromIterable(servicesToRemove)
                .flatMap(serviceId -> {
                    String routeId = serviceId + "_route";
                    log.info("Removing route for deregistered service: {}", serviceId);
                    return routeDefinitionWriter.delete(Mono.just(routeId))
                            .doOnSuccess(unused -> {
                                registeredRoutes.remove(serviceId);
                                log.info("Successfully removed route: {}", routeId);
                            })
                            .doOnError(error ->
                                    log.error("Failed to remove route {}: {}", routeId, error.getMessage())
                            )
                            .onErrorResume(e -> Mono.empty());
                })
                .then()
                .doOnTerminate(() -> {
                    // After removal, add/update routes for current services
                    addOrUpdateRoutes(currentServices);
                })
                .subscribe();
    }

    /**
     * Adds or updates routes for the given services
     */
    private void addOrUpdateRoutes(List<String> services) {
        Flux.fromIterable(services)
                .flatMap(serviceId -> {
                    String routeId = serviceId + "_route";
                    String lowerCaseServiceId = serviceId.toLowerCase();

                    boolean shouldSkip = Arrays.stream(ApiEndPointsUtil.getNotRegisterServices())
                            .anyMatch(s -> s.equalsIgnoreCase(serviceId));

                    if (shouldSkip) {
                        log.info("Skipping route modify for excluded service: {}", serviceId);
                        return Mono.empty();
                    }

                    RouteDefinition definition = createRouteDefinition(serviceId, lowerCaseServiceId);

                    // Delete existing route first, then save new one
                    return routeDefinitionWriter.delete(Mono.just(routeId))
                            .onErrorResume(e -> Mono.empty()) // Ignore if route doesn't exist
                            .then(routeDefinitionWriter.save(Mono.just(definition)))
                            .doOnSuccess(unused -> {
                                registeredRoutes.add(serviceId);
                                log.info("Route registered: {} -> lb://{}",
                                        "/" + lowerCaseServiceId + "/**", serviceId);
                            })
                            .doOnError(error ->
                                    log.error("Failed to register route for {}: {}",
                                            serviceId, error.getMessage())
                            )
                            .onErrorResume(e -> Mono.empty());
                })
                .then()
                .doOnTerminate(() -> {
                    // Publish refresh event to update the gateway
                    eventPublisher.publishEvent(new RefreshRoutesEvent(this));
                    log.info("Routes refresh completed. Active routes: {}", registeredRoutes);
                })
                .subscribe();
    }

    /**
     * Creates a RouteDefinition for the given service
     */
    private RouteDefinition createRouteDefinition(String serviceId, String lowerCaseServiceId) {
        RouteDefinition definition = new RouteDefinition();
        definition.setId(serviceId + "_route");
        definition.setUri(URI.create("lb://" + serviceId));

        // Path predicate: /service-name/**
        PredicateDefinition pathPredicate = new PredicateDefinition();
        pathPredicate.setName("Path");
        pathPredicate.setArgs(Map.of("pattern", "/" + lowerCaseServiceId + "/**"));
        definition.setPredicates(List.of(pathPredicate));

        // Optional: Add StripPrefix filter to remove service name from path
        // Uncomment if you want /service-name/api/endpoint -> /api/endpoint
        /*
        FilterDefinition stripPrefixFilter = new FilterDefinition();
        stripPrefixFilter.setName("StripPrefix");
        stripPrefixFilter.setArgs(Map.of("parts", "1"));
        definition.setFilters(List.of(stripPrefixFilter));
        */

        return definition;
    }
}