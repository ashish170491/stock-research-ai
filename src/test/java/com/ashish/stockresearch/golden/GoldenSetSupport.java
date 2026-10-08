package com.ashish.stockresearch.golden;

import com.ashish.stockresearch.agent.RequestRouter;
import com.ashish.stockresearch.agent.ResearchSessions;
import com.ashish.stockresearch.agent.ScreenerAgent;
import com.ashish.stockresearch.context.RatioContextService;
import com.ashish.stockresearch.documents.DocType;
import com.ashish.stockresearch.documents.DocumentIngestionService;
import com.ashish.stockresearch.documents.DocumentsProperties;
import com.ashish.stockresearch.documents.FixedEmbeddingModel;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.glossary.TestGlossary;
import com.ashish.stockresearch.marketdata.NseSymbolDirectory;
import com.ashish.stockresearch.marketdata.StockMarketDataService;
import com.ashish.stockresearch.marketdata.provider.yahoo.GoldenSetYahooFixtures;
import com.ashish.stockresearch.orchestrator.ResearchOrchestrator;
import com.ashish.stockresearch.research.comparison.ComparisonBuilder;
import com.ashish.stockresearch.research.comparison.ComparisonService;
import com.ashish.stockresearch.research.critic.BearCaseWriter;
import com.ashish.stockresearch.research.critic.ResearchCritic;
import com.ashish.stockresearch.research.report.AnswerChecks;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.report.ResearchReportService;
import com.ashish.stockresearch.research.report.ScreeningCountVerifier;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.screening.FundamentalsSnapshotRepository;
import com.ashish.stockresearch.screening.ScreeningRenderer;
import com.ashish.stockresearch.screening.ScreeningService;
import com.ashish.stockresearch.screening.SnapshotDatabase;
import com.ashish.stockresearch.service.AiService;
import com.ashish.stockresearch.service.OllamaCalls;
import com.ashish.stockresearch.service.ResearchReportWriter;
import com.ashish.stockresearch.tool.DocumentSearchTools;
import com.ashish.stockresearch.tool.ScreeningTools;
import com.ashish.stockresearch.tool.StockPriceTool;
import com.ashish.stockresearch.tool.StockResearchTools;
import com.ashish.stockresearch.trace.ConversationTraceAdvisor;
import com.ashish.stockresearch.trace.TraceProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;

/**
 * Wires one real {@link AiService}, using real Ollama (roadmap Step 10's golden set needs the actual model,
 * not a scripted one) over fixture-backed Yahoo data for HDFC Bank and Tech Mahindra
 * ({@link GoldenSetYahooFixtures}) - no live Yahoo call, but every other component (routing, report
 * assembly, the critic loop, the comparison narrative, verifiers) is the real production code.
 *
 * Screening and the orchestrator are out of this golden set's scope (S10 is evaluated per single-turn
 * question; a screen is deterministic Java already covered elsewhere, and a multi-step plan is not a
 * single question/answer pair) - {@link ScreenerAgent} and {@link ResearchOrchestrator} are mocked,
 * never invoked by any golden case.
 */
final class GoldenSetSupport {

    static final Clock FIXTURE_CLOCK = Clock.fixed(Instant.parse("2026-09-27T06:00:00Z"), ZoneId.of("Asia/Kolkata"));

    private GoldenSetSupport() {
    }

    /**
     * A local, unconfigured {@link OllamaApi} picks up Spring Boot's own default HTTP client timeout
     * (seconds, tuned for a hosted API) rather than the application's own, which every production
     * {@code ChatClient} gets for free from the autoconfigured beans. qwen3:8b's own interpretation and
     * critic calls routinely run past that default, so the golden set needs the same generous timeout
     * built by hand.
     */
    static OllamaApi longTimeoutOllamaApi(String baseUrl) {
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofMinutes(5));
        ReactorClientHttpRequestFactory requestFactory = new ReactorClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(5));
        return OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)))
                .build();
    }

    static AiService aiService(Path tempDir) {
        ChatModel ollamaModel = OllamaChatModel.builder()
                .ollamaApi(longTimeoutOllamaApi("http://localhost:11434"))
                .options(OllamaChatOptions.builder().model("qwen3:8b").numCtx(16384).build())
                .build();
        ChatClient.Builder chatClientBuilder = ChatClient.builder(ollamaModel);
        Advisor conversationTraceAdvisor = new ConversationTraceAdvisor(new TraceProperties(true, 600));
        OllamaCalls ollama = new OllamaCalls("qwen3:8b", "http://localhost:11434");
        MetricGlossary glossary = TestGlossary.glossary();

        GoldenSetYahooFixtures.Wiring yahoo = GoldenSetYahooFixtures.wire();
        StockResearchTools stockResearchTools = new StockResearchTools(yahoo.research(), new ResearchReportRenderer(),
                new SectorClassifier());
        StockMarketDataService marketDataService = new StockMarketDataService(yahoo.marketData());
        StockPriceTool stockPriceTool = new StockPriceTool(marketDataService);
        ScreeningTools screeningTools = new ScreeningTools(mock(ScreeningService.class), new ScreeningRenderer());
        DocumentSearchTools documentSearchTools = new DocumentSearchTools(documentStore(tempDir));

        RequestRouter router = new RequestRouter(chatClientBuilder, new NseSymbolDirectory(), glossary,
                conversationTraceAdvisor, ollama);

        ResearchReportService researchReportService = new ResearchReportService(yahoo.research(),
                new FinancialDataValidator(FIXTURE_CLOCK), new SectorClassifier());
        RatioContextService ratioContextService = new RatioContextService(
                new FundamentalsSnapshotRepository(SnapshotDatabase.create()), 5);
        NumericClaimVerifier numericVerifier = new NumericClaimVerifier();
        UnsupportedClaimFilter claimFilter = new UnsupportedClaimFilter();
        ResearchCritic critic = new ResearchCritic(chatClientBuilder, conversationTraceAdvisor, ollama);
        BearCaseWriter bearCaseWriter = new BearCaseWriter(chatClientBuilder, conversationTraceAdvisor,
                numericVerifier, claimFilter, ollama);
        ResearchReportWriter researchReportWriter = new ResearchReportWriter(chatClientBuilder,
                conversationTraceAdvisor, researchReportService, new ResearchReportRenderer(), claimFilter,
                numericVerifier, ollama, glossary, ratioContextService, critic, bearCaseWriter, false);
        ComparisonService comparisonService = new ComparisonService(chatClientBuilder, conversationTraceAdvisor,
                researchReportService, ratioContextService, new ComparisonBuilder(glossary), glossary,
                numericVerifier, claimFilter, ollama);

        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
        ResearchSessions sessions = new ResearchSessions(chatMemory);

        return new AiService(chatClientBuilder, router, stockPriceTool, stockResearchTools, screeningTools,
                documentSearchTools, conversationTraceAdvisor, researchReportWriter, comparisonService,
                marketDataService, mock(ScreenerAgent.class), new AnswerChecks(numericVerifier,
                        new ScreeningCountVerifier(), claimFilter), ollama, chatMemory, sessions, glossary,
                mock(ResearchOrchestrator.class));
    }

    /** HDFC Bank's annual report, ingested with a deterministic embedding model (no live Ollama embedding call). */
    private static SimpleVectorStore documentStore(Path tempDir) {
        Resource pdf = new ClassPathResource("fixtures/documents/hdfcbank-annual-report-fy2026.pdf");
        SimpleVectorStore store = SimpleVectorStore.builder(new FixedEmbeddingModel()).build();
        DocumentIngestionService ingestion = new DocumentIngestionService(store,
                new DocumentsProperties(tempDir.resolve("golden-vector-store.json").toString()));
        ingestion.ingest(pdf, "HDFCBANK", DocType.ANNUAL_REPORT, 2026, "HDFC Bank Annual Report FY2026.pdf");
        return store;
    }
}
