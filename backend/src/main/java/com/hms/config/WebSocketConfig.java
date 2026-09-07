package com.hms.config;

import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.HospitalWebSocketHandshakeInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * WebSocketConfig - Configures and maps WebSocket handlers with strict CORS and authentication.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private HospitalWebSocketHandler hospitalWebSocketHandler;

    @Autowired
    private HospitalWebSocketHandshakeInterceptor hospitalWebSocketHandshakeInterceptor;

    @Value("${cors.allowed.origins}")
    private String allowedOrigins;

    @Autowired private Environment environment;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Build tight CORS origin mapping matching standard HTTP configurations
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .collect(Collectors.toList());

        if (!isProduction()) {
            if (!origins.contains("http://localhost:5173")) origins.add("http://localhost:5173");
            if (!origins.contains("http://localhost:3000")) origins.add("http://localhost:3000");
        }

        registry.addHandler(hospitalWebSocketHandler, "/ws/hospital/{hospitalId}")
                .addInterceptors(hospitalWebSocketHandshakeInterceptor)
                .setAllowedOrigins(origins.toArray(new String[0]));
    }

    private boolean isProduction() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
    }
}
