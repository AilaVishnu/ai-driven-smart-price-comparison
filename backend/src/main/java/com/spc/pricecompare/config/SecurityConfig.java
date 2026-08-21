package com.spc.pricecompare.config;

import com.spc.pricecompare.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Stateless JWT security.
 *
 * <p>Browsing, searching and comparing are deliberately open: requiring an
 * account to look at prices would be hostile, and the abstract only calls for
 * authentication around personal data. So the rules protect exactly what is
 * personal - favourites, history - and leave the catalogue public.
 *
 * <p>The admin diagnostics are only mapped at all under the dev profile, and are
 * additionally restricted here, so a production deployment cannot expose raw
 * provider responses through them.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final Environment environment;

    @Value("${security.cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean devProfile = Arrays.asList(environment.getActiveProfiles()).contains("dev");

        http
                // No cookies are used, so there is no CSRF vector to protect; the
                // token travels in an Authorization header the browser never
                // attaches automatically.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/auth/register", "/api/auth/login").permitAll();
                    // Browsing the catalogue needs no account. Every public read
                    // belongs here: a path missing from this list falls through to
                    // anyRequest().authenticated() and answers 403 to anonymous
                    // callers, which reads as an empty result rather than a
                    // permissions problem.
                    auth.requestMatchers(HttpMethod.GET,
                            "/api/products/**",
                            "/api/categories",
                            "/api/platforms",
                            "/api/deals",
                            "/api/cross-platform").permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/api/compare").permitAll();
                    auth.requestMatchers("/actuator/health", "/actuator/info").permitAll();

                    if (devProfile) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                        auth.requestMatchers("/api/admin/**").permitAll();
                    } else {
                        auth.requestMatchers("/api/admin/**").hasRole("ADMIN");
                    }

                    // Everything remaining is personal to an account: favourites,
                    // search and comparison history, and the profile endpoint.
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        if (devProfile) {
            // The H2 console renders in a frame, which the default headers block.
            http.headers(headers -> headers.frameOptions(frame -> frame.disable()));
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /** BCrypt, so a database leak does not hand over usable passwords. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }
}
