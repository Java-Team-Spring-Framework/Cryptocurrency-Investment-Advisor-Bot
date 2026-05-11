package ru.spbstu.cryptoadvisor.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Spring Security configuration for the reactive HTTP API.
 *
 * <p>Replaces the previous manual {@code Authorization: Bearer <API_TOKEN>}
 * header check with standard HTTP Basic authentication:</p>
 *
 * <ul>
 *   <li><code>GET /healthcheck</code> — public</li>
 *   <li><code>GET /users</code> and <code>/admin/**</code> — require ROLE_ADMIN
 *       (HTTP Basic: <code>admin</code>/<code>admin</code> by default,
 *       configurable via <code>ADMIN_USERNAME</code> / <code>ADMIN_PASSWORD</code>)</li>
 * </ul>
 *
 * <p>Passwords are stored only as a BCrypt hash in memory; they are never
 * compared in plain text. CSRF is disabled because the API is stateless
 * and only consumed by programmatic HTTP clients.</p>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Value("${admin.password:admin}")
    private String adminPassword;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public MapReactiveUserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        if ("admin".equals(adminPassword)) {
            log.warn("Using default admin password. Set ADMIN_PASSWORD env variable in production.");
        }
        UserDetails admin = User.builder()
                .username(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .roles("ADMIN")
                .build();
        return new MapReactiveUserDetailsService(admin);
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers("/healthcheck").permitAll()
                        .pathMatchers("/users", "/admin/**").hasRole("ADMIN")
                        .anyExchange().permitAll()
                )
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
