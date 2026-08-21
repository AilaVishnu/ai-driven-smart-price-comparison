package com.spc.pricecompare.service;

import com.spc.pricecompare.domain.Role;
import com.spc.pricecompare.domain.User;
import com.spc.pricecompare.dto.Dtos;
import com.spc.pricecompare.repository.UserRepository;
import com.spc.pricecompare.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public Dtos.AuthResponse register(Dtos.RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account already exists for this email address");
        }

        User user = userRepository.save(User.builder()
                .name(request.name().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.USER)
                .build());

        return authResponse(user);
    }

    @Transactional(readOnly = true)
    public Dtos.AuthResponse login(Dtos.LoginRequest request) {
        String email = request.email().trim().toLowerCase();

        // The same message for an unknown address and a wrong password, so the
        // endpoint cannot be used to discover which addresses are registered.
        ResponseStatusException rejected = new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Email or password is incorrect");

        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow(() -> rejected);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw rejected;
        }
        return authResponse(user);
    }

    private Dtos.AuthResponse authResponse(User user) {
        String token = jwtService.generateToken(user.getEmail(), user.getId(), user.getRole().name());
        return Dtos.AuthResponse.builder()
                .token(token)
                .user(toDto(user))
                .expiresInMs(jwtService.expirationMs())
                .build();
    }

    /** The signed-in user, or empty for an anonymous request. */
    @Transactional(readOnly = true)
    public Optional<User> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return Optional.empty();
        }
        return userRepository.findByEmailIgnoreCase(authentication.getName());
    }

    /** The signed-in user, or a 401 - for endpoints that genuinely require one. */
    public User requireCurrentUser() {
        return currentUser().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Sign in to use this feature"));
    }

    public Dtos.UserDto toDto(User user) {
        return Dtos.UserDto.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
