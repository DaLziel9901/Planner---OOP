package com.example.planner.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.web.util.HttpSessionMutexListener;

import java.net.URI;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {
    private static final Pattern PLANNER_PATH = Pattern.compile(
            "/(?:plans|subplans|tasks|subtasks|ai/plans)(?:/[a-zA-Z0-9_-]+)*/?");

    @Bean
    HttpSessionMutexListener sessionMutexListener() {
        return new HttpSessionMutexListener();
    }

    @Bean
    SecurityFilterChain plannerSecurity(HttpSecurity http, PlannerUserDetailsService users, PasswordEncoder passwords)
            throws Exception {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        var cache = new HttpSessionRequestCache();
        cache.setMatchingRequestParameterName(null);
        cache.setRequestMatcher(request -> "GET".equals(request.getMethod()) && PLANNER_PATH.matcher(
                request.getRequestURI().substring(request.getContextPath().length())).matches());
        var redirects = new DefaultRedirectStrategy();
        AuthorizationManager<RequestAuthorizationContext> plannerAccount = (authentication, context) -> {
            var current = authentication.get();
            return new AuthorizationDecision(current != null && current.isAuthenticated()
                    && current.getPrincipal() instanceof PlannerPrincipal principal && principal.id() > 0);
        };

        return http.authenticationProvider(provider)
                .requestCache(requests -> requests.requestCache(cache))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/login", "/register", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.POST, "/logout").access(plannerAccount)
                        .requestMatchers("/", "/plans/**", "/subplans/**", "/tasks/**", "/subtasks/**",
                                "/ai/plans/**").access(plannerAccount)
                        .anyRequest().denyAll())
                .formLogin(login -> login.loginPage("/login").failureUrl("/login?error")
                        .successHandler((request, response, authentication) -> {
                            var saved = cache.getRequest(request, response);
                            cache.removeRequest(request, response);
                            String target = "/plans";
                            if (saved != null) {
                                URI uri = URI.create(saved.getRedirectUrl());
                                String path = uri.getRawPath();
                                String context = request.getContextPath();
                                if (path != null && path.startsWith(context)
                                        && PLANNER_PATH.matcher(path.substring(context.length())).matches()) {
                                    target = path.substring(context.length());
                                    if (uri.getRawQuery() != null) {
                                        target += "?" + uri.getRawQuery();
                                    }
                                }
                            }
                            redirects.sendRedirect(request, response, target);
                        }))
                .logout(logout -> logout.logoutSuccessUrl("/login"))
                .build();
    }
}
