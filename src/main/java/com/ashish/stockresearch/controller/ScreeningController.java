package com.ashish.stockresearch.controller;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshotRepository;
import com.ashish.stockresearch.screening.PresetView;
import com.ashish.stockresearch.screening.ScreeningOutcome;
import com.ashish.stockresearch.screening.ScreeningPresets;
import com.ashish.stockresearch.screening.ScreeningRenderer;
import com.ashish.stockresearch.screening.ScreeningService;
import com.ashish.stockresearch.screening.ScreeningView;
import com.ashish.stockresearch.screening.SnapshotJob;
import com.ashish.stockresearch.screening.SnapshotRun;
import com.ashish.stockresearch.screening.Universe;
import com.ashish.stockresearch.screening.Universes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Screening over HTTP: the same table the {@code screenStocks} tool gives the model (as Markdown, or as
 * JSON for the web page), the presets to choose from, and the admin endpoints that start a snapshot run
 * and report on the latest one.
 *
 * <p>The admin endpoints have no authentication: this application is meant to run locally.
 */
@RestController
public class ScreeningController {

    private final ScreeningService screeningService;
    private final ScreeningRenderer renderer;
    private final SnapshotJob snapshotJob;
    private final FundamentalsSnapshotRepository repository;
    private final ScreeningPresets presets;
    private final Universes universes;

    public ScreeningController(ScreeningService screeningService, ScreeningRenderer renderer, SnapshotJob snapshotJob,
                               FundamentalsSnapshotRepository repository, ScreeningPresets presets,
                               Universes universes) {
        this.screeningService = screeningService;
        this.renderer = renderer;
        this.snapshotJob = snapshotJob;
        this.repository = repository;
        this.presets = presets;
        this.universes = universes;
    }

    @GetMapping(value = "/api/screening", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> screen(@RequestParam String preset,
                                         @RequestParam(required = false) String universe,
                                         @RequestParam(required = false) String industryGroup) {
        ScreeningOutcome outcome = screeningService.screen(preset, universe, industryGroup);
        HttpStatus status = switch (outcome.status()) {
            case OK -> HttpStatus.OK;
            case NO_SNAPSHOT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(renderer.render(outcome));
    }

    /** The same screen as JSON, laid out for the web page. */
    @GetMapping("/api/screening/data")
    public ResponseEntity<ScreeningView> screenData(@RequestParam String preset,
                                                    @RequestParam(required = false) String universe,
                                                    @RequestParam(required = false) String industryGroup) {
        ScreeningOutcome outcome = screeningService.screen(preset, universe, industryGroup);
        HttpStatus status = switch (outcome.status()) {
            case OK -> HttpStatus.OK;
            case NO_SNAPSHOT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ScreeningView.from(outcome));
    }

    public record Options(List<PresetView> presets, List<UniverseChoice> universes, List<Choice> industryGroups) {
    }

    /** @param members how many companies the universe has, from its constituents file */
    public record UniverseChoice(String value, String label, int members) {
    }

    public record Choice(String value, String label) {
    }

    /** What a screen can be run with: the presets and their criteria, the universes and the industry groups. */
    @GetMapping("/api/screening/options")
    public Options options() {
        return new Options(presets.names().stream().map(name -> PresetView.from(presets.find(name).orElseThrow()))
                .toList(),
                java.util.Arrays.stream(Universe.values())
                        .map(u -> new UniverseChoice(u.name(), u.displayName(), universes.members(u).size())).toList(),
                java.util.Arrays.stream(IndustryGroup.values()).filter(g -> g != IndustryGroup.UNKNOWN)
                        .map(g -> new Choice(g.name(), g.name())).toList());
    }

    /** @param filedYears how many stored companies have six fiscal years from their filings; null while starting */
    public record SnapshotStatus(SnapshotRun run, boolean running, SnapshotRun.FiledYears filedYears,
                                 List<SnapshotRun.Failure> failures) {
    }

    /** Starts a snapshot run in the background; 409 if one is already running. */
    @PostMapping("/api/admin/snapshot")
    public ResponseEntity<SnapshotStatus> startSnapshot(@RequestParam(required = false) String universe) {
        Universe target = universe(universe);
        return snapshotJob.startInBackground(target)
                .map(run -> ResponseEntity.status(HttpStatus.ACCEPTED).body(new SnapshotStatus(run, true, null, List.of())))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "A snapshot run is already in progress"));
    }

    @GetMapping("/api/admin/snapshot")
    public SnapshotStatus snapshotStatus(@RequestParam(required = false) String universe) {
        Universe target = universe(universe);
        SnapshotRun run = repository.latestRun(target).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No snapshot of %s has been started yet".formatted(target.displayName())));
        // the job runs one universe at a time: another universe's run is not this one refreshing
        boolean running = snapshotJob.running() && run.status() == SnapshotRun.Status.RUNNING;
        return new SnapshotStatus(run, running, repository.filedYears(run.id()),
                repository.failures(run.id()));
    }

    private static Universe universe(String name) {
        if (name == null || name.isBlank()) {
            return Universe.DEFAULT;
        }
        return Universe.parse(name).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown universe '%s'".formatted(name)));
    }
}
