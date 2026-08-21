package com.spc.pricecompare.service;

import com.spc.pricecompare.ai.TopsisScoringService;
import com.spc.pricecompare.ai.TopsisScoringService.Criterion;
import com.spc.pricecompare.domain.ComparisonHistory;
import com.spc.pricecompare.domain.Product;
import com.spc.pricecompare.domain.User;
import com.spc.pricecompare.dto.Dtos;
import com.spc.pricecompare.repository.ComparisonHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Side-by-side comparison, ranked by TOPSIS.
 *
 * <p>Weights arrive from the interface sliders, so the same four products can
 * legitimately produce different winners for different shoppers. That is the
 * feature, not a defect: a decision support system that hides its assumptions
 * is just an opinion. Every response therefore carries the weights it used and
 * a per-criterion breakdown of why the winner won.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComparisonService {

    /** More than this and the comparison table stops being readable. */
    private static final int MAX_PRODUCTS = 4;

    private final ProductService productService;
    private final ScoringInputBuilder scoringInputBuilder;
    private final TopsisScoringService topsisService;
    private final ComparisonHistoryRepository comparisonHistoryRepository;

    @Transactional
    public Dtos.CompareResponse compare(List<Long> productIds,
                                        Map<String, Double> requestedWeights,
                                        User user) {
        if (productIds == null || productIds.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At least two products are needed for a comparison");
        }
        if (productIds.size() > MAX_PRODUCTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At most " + MAX_PRODUCTS + " products can be compared at once");
        }

        List<Long> distinctIds = productIds.stream().distinct().toList();
        List<Product> products = productService.findAllByIds(distinctIds);

        if (products.size() < 2) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Could not find enough of the requested products to compare");
        }

        List<TopsisScoringService.Alternative> alternatives = products.stream()
                .map(p -> scoringInputBuilder.toAlternative(p, new ArrayList<>(p.getOffers())))
                .toList();

        TopsisScoringService.Result result = topsisService.rank(alternatives, translateWeights(requestedWeights));

        List<Dtos.ProductDetailDto> details = products.stream()
                .map(p -> productService.getDetail(p.getId(), 90))
                .toList();

        Long winnerId = result.ranked().isEmpty() ? null : result.ranked().get(0).productId();
        String reason = result.ranked().isEmpty() ? null : explainWinner(result.ranked().get(0));

        if (user != null && winnerId != null) {
            recordHistory(user, distinctIds, winnerId);
        }

        return Dtos.CompareResponse.builder()
                .products(details)
                .ranking(result.ranked())
                .winnerProductId(winnerId)
                .winnerReason(reason)
                .weightsUsed(result.weightsUsed())
                .note(result.note())
                .build();
    }

    /**
     * Puts the verdict in words by naming the criteria the winner actually led
     * on, rather than asserting a score and leaving the user to take it on
     * trust.
     */
    private String explainWinner(TopsisScoringService.Scored winner) {
        List<String> leads = winner.breakdown().values().stream()
                .filter(TopsisScoringService.CriterionDetail::isBest)
                .map(TopsisScoringService.CriterionDetail::label)
                .toList();

        if (leads.isEmpty()) {
            return "Best overall balance across the weighted criteria, "
                    + "without leading on any single one";
        }
        if (leads.size() == 1) {
            return "Leads on " + leads.get(0).toLowerCase()
                    + ", and holds up well enough elsewhere to come out ahead";
        }
        String joined = String.join(", ", leads.subList(0, leads.size() - 1))
                + " and " + leads.get(leads.size() - 1);
        return "Best on " + joined.toLowerCase();
    }

    private Map<Criterion, Double> translateWeights(Map<String, Double> requested) {
        if (requested == null || requested.isEmpty()) {
            return topsisService.defaultWeights();
        }
        Map<Criterion, Double> weights = new EnumMap<>(Criterion.class);
        requested.forEach((key, value) -> {
            Criterion criterion = Criterion.fromKey(key);
            if (criterion != null && value != null) {
                weights.put(criterion, value);
            }
        });
        return weights.isEmpty() ? topsisService.defaultWeights() : weights;
    }

    private void recordHistory(User user, List<Long> productIds, Long winnerId) {
        try {
            String json = productIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",", "[", "]"));
            comparisonHistoryRepository.save(ComparisonHistory.builder()
                    .user(user)
                    .productIdsJson(json)
                    .winnerProductId(winnerId)
                    .build());
        } catch (Exception e) {
            // History is a convenience; failing to record it must not fail the
            // comparison the user asked for.
            log.warn("Could not record comparison history: {}", e.toString());
        }
    }
}
