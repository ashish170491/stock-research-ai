package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.service.ResearchReportWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Last-resort ticker lookup that asks the LLM directly when the
 * direct-symbol-guess and Yahoo-search resolution chain both fail - e.g.
 * company names whose ticker mnemonic isn't a simple concatenation of the
 * name, such as "Bharti Airtel" -> BHARTIARTL.
 *
 * Deliberately built WITHOUT the stock-quote tool registered, so this
 * internal lookup can never recurse back into getStockQuote. Whatever
 * ticker it suggests is only a candidate - the caller must still verify it
 * against a real market-data quote before using it, since an LLM can
 * hallucinate a plausible-looking but wrong symbol.
 */
@Component
class LlmSymbolResolver {

    private static final Logger log = LoggerFactory.getLogger(LlmSymbolResolver.class);
    private static final Pattern PLAUSIBLE_TICKER = Pattern.compile("^[A-Z0-9&-]{1,20}$");

    private final ChatClient chatClient;

    LlmSymbolResolver(ChatClient.Builder chatClientBuilder) {
        // A one-word answer: no thinking, no sampling variety, a small context.
        this.chatClient = chatClientBuilder.clone()
                .defaultOptions(OllamaChatOptions.builder().disableThinking().temperature(0.0).numCtx(2048))
                .build();
    }

    Optional<String> resolveTickerGuess(String companyName) {
        String response;
        try {
            response = chatClient.prompt("""
                    Give the exact NSE (National Stock Exchange of India) trading symbol for this company: "%s"
                    Respond with ONLY the ticker symbol in uppercase - no explanation, no punctuation, no exchange suffix like .NS.
                    If you are not confident or the company is not listed in India, respond with exactly: UNKNOWN
                    """.formatted(companyName))
                    .call()
                    .content();
        } catch (RuntimeException ex) {
            log.debug("LLM symbol lookup failed for '{}': {}", companyName, ex.getMessage());
            return Optional.empty();
        }

        // Qwen3 can emit its reasoning inline; only the answer after it is a candidate.
        String answer = ResearchReportWriter.stripThinking(response);
        String candidate = answer == null ? "" : answer.strip().toUpperCase();
        if (candidate.isEmpty() || "UNKNOWN".equals(candidate) || !PLAUSIBLE_TICKER.matcher(candidate).matches()) {
            log.debug("LLM symbol lookup for '{}' returned no usable candidate: '{}'", companyName, response);
            return Optional.empty();
        }

        log.info("LLM symbol lookup for '{}' suggested candidate ticker '{}'", companyName, candidate);
        return Optional.of(candidate);
    }
}
