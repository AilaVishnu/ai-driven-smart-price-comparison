package com.spc.pricecompare.web;

import com.spc.pricecompare.dto.Dtos;
import com.spc.pricecompare.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<Dtos.AuthResponse> register(@Valid @RequestBody Dtos.RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public Dtos.AuthResponse login(@Valid @RequestBody Dtos.LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public Dtos.UserDto me() {
        return authService.toDto(authService.requireCurrentUser());
    }
}
