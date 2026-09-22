package com.ashish.stockresearch.tool;

import com.ashish.stockresearch.marketdata.StockMarketDataService;
import com.ashish.stockresearch.marketdata.model.StockQuoteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockPriceToolTest {

    @Mock
    private StockMarketDataService stockMarketDataService;

    @Test
    void delegatesSymbolToStockMarketDataService() {
        StockQuoteResult expected = StockQuoteResult.error("Unsupported symbol");
        when(stockMarketDataService.getQuote("RELIANCE")).thenReturn(expected);

        StockPriceTool tool = new StockPriceTool(stockMarketDataService);
        StockQuoteResult result = tool.getStockQuote("RELIANCE");

        assertThat(result).isEqualTo(expected);
        verify(stockMarketDataService).getQuote("RELIANCE");
    }

    @Test
    void containsNoBusinessLogicOfItsOwn() {
        StockQuoteResult expected = StockQuoteResult.error("Market data is currently unavailable");
        when(stockMarketDataService.getQuote("TCS")).thenReturn(expected);

        StockPriceTool tool = new StockPriceTool(stockMarketDataService);

        assertThat(tool.getStockQuote("TCS").success()).isFalse();
    }
}
