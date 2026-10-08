package com.example.crediSense.jwt;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth

                        // ── OPTIONS (CORS preflight) ──────────────────────────
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // ── Swagger ───────────────────────────────────────────
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/swagger-ui.html"
                        ).permitAll()
//
                        // ── Auth agents / admins ──────────────────────────────
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/init-admin").permitAll()
                        .requestMatchers("/api/auth/register").hasRole("ADMIN")
                        .requestMatchers("/api/auth/forgot-password").permitAll()
                        .requestMatchers("/api/auth/reset-password").permitAll()

                        // ── Auth clients (portail) ────────────────────────────
                        .requestMatchers("/api/client-auth/**").permitAll()
                                .requestMatchers("/api/dossiers/*/send-result-email").permitAll()

                        // ── Portail client (public) ───────────────────────────
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/public/upload/**").permitAll()  // ✅
                        .requestMatchers("/api/clients/historique").permitAll()
                        // Propositions d'ajustement : réservées au client connecté, sur ses propres demandes
                        .requestMatchers("/api/clients/mes-demandes/**").hasRole("CLIENT")
                        .requestMatchers("/api/clients/**").permitAll()

                        // ── Fichiers & chatbot ────────────────────────────────
                        .requestMatchers("/api/fichiers/**").permitAll()
                        .requestMatchers("/api/fichiers/view/**").permitAll()  // ✅ ajoutez
                        .requestMatchers("/api/chatbot/**").permitAll()
                        .requestMatchers("/api/fichiers/analyser-dossier/**").permitAll()

                        // ── Dossiers → agents et admins seulement ────────────
                        // (les jetons CLIENT sont reconnus par JwtAuthFilter : un client connecté ne doit
                        // pas pouvoir atteindre les routes des agents)
                        .requestMatchers("/api/dossiers/**").hasAnyRole("ADMIN", "AGENT")
                        // ── Admin ─────────────────────────────────────────────
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // ── Tout le reste → agents et admins ──────────────────
                        .anyRequest().hasAnyRole("ADMIN", "AGENT")
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(java.util.Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Use JWT authentication");
        };
    }
}