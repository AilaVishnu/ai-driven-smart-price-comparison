package com.spc.pricecompare.web;

import com.spc.pricecompare.dto.Dtos;
import com.spc.pricecompare.service.AccountService;
import com.spc.pricecompare.service.AuthService;
import com.spc.pricecompare.service.ComparisonService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/compare")
@RequiredArgsConstructor
public class CompareController {

    private final ComparisonService comparisonService;
    private final AccountService accountService;
    private final AuthService authService;

    /**
     * Ranks two to four products with TOPSIS.
     *
     * <p>Open to anonymous users: comparing prices should not require an account.
     * A signed-in user additionally gets the comparison saved to their history.
     */
    @PostMapping
    public Dtos.CompareResponse compare(@Valid @RequestBody Dtos.CompareRequest request) {
        return comparisonService.compare(
                request.productIds(),
                request.weights(),
                authService.currentUser().orElse(null));
    }

    @GetMapping("/history")
    public List<Dtos.ComparisonHistoryDto> history() {
        return accountService.comparisonHistory(authService.requireCurrentUser());
    }
}
