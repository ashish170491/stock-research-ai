package com.ashish.stockresearch.research;

import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** A {@link FiledAnnualHistoryService} over no filings source, or over a given one, for tests. */
public final class TestFilings {

    private TestFilings() {
    }

    /** No filings source: the provider's own fiscal-year figures stand, as in the mock profile. */
    public static FiledAnnualHistoryService none() {
        return new FiledAnnualHistoryService(new StaticListableBeanFactory().getBeanProvider(FiledFinancialsProvider.class));
    }

    public static FiledAnnualHistoryService of(FiledFinancialsProvider provider) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("filings", provider);
        return new FiledAnnualHistoryService(beans.getBeanProvider(FiledFinancialsProvider.class));
    }
}
