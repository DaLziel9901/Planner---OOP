package com.example.planner.security;

import com.example.planner.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class PlannerUserDetailsService implements UserDetailsService {
    private final UserRepository users;

    public PlannerUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        String normalized = UsernameNormalizer.normalize(username);
        if (!UsernameNormalizer.isValid(normalized)) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        return users.findByUsername(normalized)
                .map(user -> new PlannerPrincipal(user.id(), user.username(), user.passwordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
