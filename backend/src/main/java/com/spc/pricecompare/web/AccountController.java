package com.spc.pricecompare.web;

import com.spc.pricecompare.dto.Dtos;
import com.spc.pricecompare.service.AccountService;
import com.spc.pricecompare.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final AuthService authService;

    @GetMapping("/favorites")
    public List<Dtos.FavoriteDto> favorites() {
        return accountService.listFavorites(authService.requireCurrentUser());
    }

    @PostMapping("/favorites/{productId}")
    public Dtos.FavoriteDto addFavorite(@PathVariable Long productId) {
        return accountService.addFavorite(authService.requireCurrentUser(), productId);
    }

    @DeleteMapping("/favorites/{productId}")
    public ResponseEntity<Void> removeFavorite(@PathVariable Long productId) {
        accountService.removeFavorite(authService.requireCurrentUser(), productId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/favorites/{productId}/status")
    public Map<String, Boolean> favoriteStatus(@PathVariable Long productId) {
        return Map.of("favorite",
                accountService.isFavorite(authService.requireCurrentUser(), productId));
    }

    @GetMapping("/history/search")
    public List<Dtos.SearchHistoryDto> searchHistory() {
        return accountService.searchHistory(authService.requireCurrentUser());
    }
}
