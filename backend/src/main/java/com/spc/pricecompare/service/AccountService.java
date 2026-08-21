package com.spc.pricecompare.service;

import com.spc.pricecompare.domain.ComparisonHistory;
import com.spc.pricecompare.domain.Favorite;
import com.spc.pricecompare.domain.Product;
import com.spc.pricecompare.domain.SearchHistory;
import com.spc.pricecompare.domain.User;
import com.spc.pricecompare.dto.Dtos;
import com.spc.pricecompare.repository.ComparisonHistoryRepository;
import com.spc.pricecompare.repository.FavoriteRepository;
import com.spc.pricecompare.repository.ProductRepository;
import com.spc.pricecompare.repository.SearchHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The per-account features: favourites, search history and comparison history.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private static final int HISTORY_PAGE_SIZE = 50;

    private final FavoriteRepository favoriteRepository;
    private final SearchHistoryRepository searchHistoryRepository;
    private final ComparisonHistoryRepository comparisonHistoryRepository;
    private final ProductRepository productRepository;
    private final ProductMapper mapper;

    @Transactional(readOnly = true)
    public List<Dtos.FavoriteDto> listFavorites(User user) {
        return favoriteRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(f -> Dtos.FavoriteDto.builder()
                        .id(f.getId())
                        .product(mapper.toSummary(f.getProduct(),
                                new ArrayList<>(f.getProduct().getOffers()), null))
                        .createdAt(f.getCreatedAt())
                        .build())
                .toList();
    }

    @Transactional
    public Dtos.FavoriteDto addFavorite(User user, Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No product with id " + productId));

        // Favouriting something already favourited is not an error; return the
        // existing row so the endpoint is idempotent.
        Favorite favorite = favoriteRepository
                .findByUserIdAndProductId(user.getId(), productId)
                .orElseGet(() -> favoriteRepository.save(Favorite.builder()
                        .user(user)
                        .product(product)
                        .build()));

        return Dtos.FavoriteDto.builder()
                .id(favorite.getId())
                .product(mapper.toSummary(product, new ArrayList<>(product.getOffers()), null))
                .createdAt(favorite.getCreatedAt())
                .build();
    }

    @Transactional
    public void removeFavorite(User user, Long productId) {
        favoriteRepository.deleteByUserIdAndProductId(user.getId(), productId);
    }

    @Transactional(readOnly = true)
    public boolean isFavorite(User user, Long productId) {
        return favoriteRepository.existsByUserIdAndProductId(user.getId(), productId);
    }

    /**
     * Records a search. Anonymous searches are stored unattributed, which keeps
     * the popular-query data useful without tying it to anyone.
     */
    @Transactional
    public void recordSearch(User user, String query, int resultCount) {
        if (query == null || query.isBlank()) {
            return;
        }
        try {
            searchHistoryRepository.save(SearchHistory.builder()
                    .user(user)
                    .query(query.length() > 300 ? query.substring(0, 300) : query)
                    .resultCount(resultCount)
                    .build());
        } catch (Exception e) {
            // Never let history bookkeeping fail a search.
            log.debug("Could not record search history: {}", e.toString());
        }
    }

    @Transactional(readOnly = true)
    public List<Dtos.SearchHistoryDto> searchHistory(User user) {
        return searchHistoryRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, HISTORY_PAGE_SIZE))
                .stream()
                .map(h -> Dtos.SearchHistoryDto.builder()
                        .id(h.getId())
                        .query(h.getQuery())
                        .resultCount(h.getResultCount())
                        .createdAt(h.getCreatedAt())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Dtos.ComparisonHistoryDto> comparisonHistory(User user) {
        return comparisonHistoryRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, HISTORY_PAGE_SIZE))
                .stream()
                .map(h -> Dtos.ComparisonHistoryDto.builder()
                        .id(h.getId())
                        .productIds(parseIds(h))
                        .winnerProductId(h.getWinnerProductId())
                        .createdAt(h.getCreatedAt())
                        .build())
                .toList();
    }

    /** Brands the user has favourited, used to personalise recommendations. */
    @Transactional(readOnly = true)
    public List<String> preferredBrands(User user) {
        return favoriteRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(f -> f.getProduct().getBrand())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private static List<Long> parseIds(ComparisonHistory history) {
        String json = history.getProductIdsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            String inner = json.replace("[", "").replace("]", "").trim();
            if (inner.isEmpty()) {
                return List.of();
            }
            return Arrays.stream(inner.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::valueOf)
                    .toList();
        } catch (NumberFormatException e) {
            log.debug("Unparseable comparison history ids: {}", json);
            return List.of();
        }
    }
}
