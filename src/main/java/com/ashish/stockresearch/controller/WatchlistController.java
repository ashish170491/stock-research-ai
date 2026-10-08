package com.ashish.stockresearch.controller;

import com.ashish.stockresearch.screening.ScreeningPresets;
import com.ashish.stockresearch.screening.Universe;
import com.ashish.stockresearch.watchlist.SavedScreen;
import com.ashish.stockresearch.watchlist.SavedScreenRepository;
import com.ashish.stockresearch.watchlist.WatchlistService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Saved screens for watchlist monitoring, and what entered or left each since the previous snapshot (S8-6). */
@RestController
public class WatchlistController {

    private final SavedScreenRepository savedScreens;
    private final WatchlistService watchlistService;
    private final ScreeningPresets presets;

    public WatchlistController(SavedScreenRepository savedScreens, WatchlistService watchlistService,
            ScreeningPresets presets) {
        this.savedScreens = savedScreens;
        this.watchlistService = watchlistService;
        this.presets = presets;
    }

    public record SaveRequest(String name, String universe, String preset, String industryGroup) {
    }

    @PostMapping("/api/watchlist")
    public SavedScreen save(@RequestBody SaveRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A saved screen needs a name.");
        }
        if (presets.find(request.preset()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown screening preset '"
                    + request.preset() + "'. Available presets: " + String.join(", ", presets.names()) + ".");
        }
        Universe universe = request.universe() == null || request.universe().isBlank() ? Universe.DEFAULT
                : Universe.parse(request.universe()).orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Unknown universe '" + request.universe() + "'."));
        return savedScreens.save(request.name().strip(), universe, request.preset(), request.industryGroup());
    }

    @GetMapping("/api/watchlist")
    public List<SavedScreen> list() {
        return savedScreens.findAll();
    }

    @GetMapping("/api/watchlist/diffs")
    public List<WatchlistService.WatchlistDiff> diffs() {
        return watchlistService.diffAll();
    }
}
