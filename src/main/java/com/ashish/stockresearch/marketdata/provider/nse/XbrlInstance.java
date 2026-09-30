package com.ashish.stockresearch.marketdata.provider.nse;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The facts of one XBRL instance document, as NSE's results filings are published: each fact's concept,
 * context, unit, precision and value, and each context's period.
 *
 * <p>A context's period is taken from the filing's own {@code DateOfStartOfReportingPeriod} and
 * {@code DateOfEndOfReportingPeriod} facts in that context where it has them. NSE's older results format
 * gives every duration context the quarter's dates in its {@code xbrli:period}, even the full-year column,
 * so the XBRL period alone would read a year's revenue as the quarter's.
 *
 * <p>Only facts without dimensions (no segment or scenario) are the statement's own lines; a segment's
 * revenue is never read as the company's. A concept found more than once for the same period with
 * different values is {@link Lookup.Ambiguous}: neither value is chosen.
 *
 * <p>Parsed with DTDs and external entities disabled: a document that declares a DOCTYPE is refused, so a
 * downloaded file can never make the parser read local files or other URLs.
 */
final class XbrlInstance {

    private static final String XBRLI = "http://www.xbrl.org/2003/instance";
    private static final Set<String> NOT_FACTS = Set.of(XBRLI, "http://www.xbrl.org/2003/linkbase",
            "http://www.w3.org/1999/xlink");
    /** The exchange taxonomy's year-to-date column. */
    private static final String YEAR_TO_DATE = "FourD";

    record Context(String id, LocalDate start, LocalDate end, LocalDate instant, boolean dimensional) {

        boolean isDuration(LocalDate from, LocalDate to) {
            return !dimensional && from.equals(start) && to.equals(end);
        }

        boolean isInstant(LocalDate date) {
            return !dimensional && date.equals(instant);
        }
    }

    record Fact(String concept, String contextRef, String unitRef, String decimals, String value) {
    }

    /** What looking up one concept for one period found. */
    sealed interface Lookup {

        /** @param decimals the XBRL precision: -7 means rounded to the nearest crore; null for exact ("INF") */
        record Found(BigDecimal value, Integer decimals, String unitRef) implements Lookup {
        }

        record Missing() implements Lookup {
        }

        record Ambiguous(List<BigDecimal> values) implements Lookup {
        }

        /** The fact is there but is not a number. */
        record NotANumber(String text) implements Lookup {
        }
    }

    private final Map<String, Context> contexts;
    private final List<Fact> facts;

    private XbrlInstance(Map<String, Context> contexts, List<Fact> facts) {
        this.contexts = contexts;
        this.facts = facts;
    }

    static XbrlInstance parse(byte[] xml) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        Map<String, RawContext> raw = new HashMap<>();
        List<Fact> facts = new ArrayList<>();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            int depth = 0;
            RawContext context = null;
            String periodPart = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) {
                    throw new IllegalArgumentException("XBRL document declares a DOCTYPE; refused");
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String ns = reader.getNamespaceURI();
                    String name = reader.getLocalName();
                    if (XBRLI.equals(ns) && "context".equals(name)) {
                        context = new RawContext(reader.getAttributeValue(null, "id"));
                    } else if (context != null && XBRLI.equals(ns)
                            && ("segment".equals(name) || "scenario".equals(name))) {
                        context.dimensional = true;
                    } else if (context != null && XBRLI.equals(ns)
                            && ("startDate".equals(name) || "endDate".equals(name) || "instant".equals(name))) {
                        periodPart = name;
                    } else if (depth == 2 && context == null && !NOT_FACTS.contains(ns)
                            && reader.getAttributeValue(null, "contextRef") != null) {
                        String contextRef = reader.getAttributeValue(null, "contextRef");
                        String unitRef = reader.getAttributeValue(null, "unitRef");
                        String decimals = reader.getAttributeValue(null, "decimals");
                        String value = reader.getElementText();
                        depth--;
                        facts.add(new Fact(name, contextRef, unitRef, decimals, value == null ? "" : value.strip()));
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && context != null && periodPart != null) {
                    context.set(periodPart, reader.getText().strip());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                    if (XBRLI.equals(reader.getNamespaceURI())) {
                        if ("context".equals(reader.getLocalName()) && context != null) {
                            raw.put(context.id, context);
                            context = null;
                        } else if (reader.getLocalName().equals(periodPart)) {
                            periodPart = null;
                        }
                    }
                }
            }
        } catch (XMLStreamException ex) {
            throw new IllegalArgumentException("Not a readable XBRL document: " + ex.getMessage(), ex);
        }
        return new XbrlInstance(contexts(raw, facts), List.copyOf(facts));
    }

    /** The contexts, with each duration context's period as the filing's own reporting-period facts state it. */
    private static Map<String, Context> contexts(Map<String, RawContext> raw, List<Fact> facts) {
        Map<String, String> reportingStart = new HashMap<>();
        Map<String, String> reportingEnd = new HashMap<>();
        for (Fact fact : facts) {
            if ("DateOfStartOfReportingPeriod".equals(fact.concept())) {
                reportingStart.put(fact.contextRef(), fact.value());
            } else if ("DateOfEndOfReportingPeriod".equals(fact.concept())) {
                reportingEnd.put(fact.contextRef(), fact.value());
            }
        }
        Map<String, Context> contexts = new HashMap<>();
        raw.forEach((id, context) -> {
            LocalDate start = date(reportingStart.getOrDefault(id, context.start));
            LocalDate end = date(reportingEnd.getOrDefault(id, context.end));
            contexts.put(id, new Context(id, start, end, date(context.instant), context.dimensional));
        });
        // NSE's 2021-22 documents refer to their main columns (OneD, FourD) without defining them. A column
        // whose own reporting-period facts state both its dates is placed by them; the statement's lines are
        // the only facts that carry those dates. A column stating neither date, such as the undefined
        // balance-sheet column, stays unplaced: its date is never inferred.
        for (String id : reportingStart.keySet()) {
            if (!contexts.containsKey(id) && reportingEnd.containsKey(id)) {
                LocalDate start = date(reportingStart.get(id));
                LocalDate end = date(reportingEnd.get(id));
                if (start != null && end != null) {
                    contexts.put(id, new Context(id, start, end, null, false));
                }
            }
        }
        // The banking format of FY21-FY23 dates its undefined year-to-date column (FourD in the exchange's
        // taxonomy) by its end alone, and states the financial year separately. When that column ends on the
        // last day of the financial year the document states, its year to date is that financial year.
        LocalDate yearStart = date(single(facts, "DateOfStartOfFinancialYear"));
        LocalDate yearEnd = date(single(facts, "DateOfEndOfFinancialYear"));
        if (!contexts.containsKey(YEAR_TO_DATE) && !reportingStart.containsKey(YEAR_TO_DATE)
                && yearStart != null && yearEnd != null && yearEnd.equals(date(reportingEnd.get(YEAR_TO_DATE)))) {
            contexts.put(YEAR_TO_DATE, new Context(YEAR_TO_DATE, yearStart, yearEnd, null, false));
        }
        return Map.copyOf(contexts);
    }

    /** The one distinct value the document states for a concept, in any column; null if none or several. */
    private static String single(List<Fact> facts, String concept) {
        Set<String> values = new LinkedHashSet<>();
        for (Fact fact : facts) {
            if (fact.concept().equals(concept) && !fact.value().isBlank()) {
                values.add(fact.value().strip());
            }
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }

    private static LocalDate date(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** The concept's value over exactly this period, from facts without dimensions. */
    Lookup duration(String concept, LocalDate start, LocalDate end) {
        return lookup(concept, context -> context.isDuration(start, end));
    }

    /** The concept's value at this date, from facts without dimensions. */
    Lookup instant(String concept, LocalDate date) {
        return lookup(concept, context -> context.isInstant(date));
    }

    private Lookup lookup(String concept, java.util.function.Predicate<Context> inPeriod) {
        List<Fact> matching = facts.stream().filter(fact -> fact.concept().equals(concept))
                .filter(fact -> {
                    Context context = contexts.get(fact.contextRef());
                    return context != null && inPeriod.test(context);
                })
                .toList();
        if (matching.isEmpty()) {
            return new Lookup.Missing();
        }
        Set<BigDecimal> values = new LinkedHashSet<>();
        for (Fact fact : matching) {
            try {
                values.add(new BigDecimal(fact.value()).stripTrailingZeros());
            } catch (NumberFormatException ex) {
                return new Lookup.NotANumber(fact.value());
            }
        }
        if (values.size() > 1) {
            return new Lookup.Ambiguous(List.copyOf(values));
        }
        Fact fact = matching.get(0);
        return new Lookup.Found(new BigDecimal(fact.value()), decimals(fact.decimals()), fact.unitRef());
    }

    private static Integer decimals(String decimals) {
        if (decimals == null || decimals.isBlank() || "INF".equalsIgnoreCase(decimals.strip())) {
            return null;
        }
        try {
            return Integer.parseInt(decimals.strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * The text of a non-numeric fact without dimensions ("Consolidated", "INR"), if the filing states one value.
     * A statement about the whole document counts in an undefined column too (the old banking format states
     * its currency only there): it can only add a value, and two different values are no answer.
     */
    Optional<String> text(String concept) {
        Set<String> values = new LinkedHashSet<>();
        for (Fact fact : facts) {
            Context context = contexts.get(fact.contextRef());
            if (fact.concept().equals(concept) && (context == null || !context.dimensional()) && !fact.value().isBlank()) {
                values.add(fact.value());
            }
        }
        return values.size() == 1 ? Optional.of(values.iterator().next()) : Optional.empty();
    }

    /** The non-dimensional duration periods the filing reports, e.g. the quarter and the fiscal year to date. */
    Set<List<LocalDate>> durationPeriods() {
        Set<List<LocalDate>> periods = new LinkedHashSet<>();
        for (Fact fact : facts) {
            Context context = contexts.get(fact.contextRef());
            if (context != null && !context.dimensional() && context.start() != null && context.end() != null) {
                periods.add(List.of(context.start(), context.end()));
            }
        }
        return periods;
    }

    /** Contexts the facts refer to that the document neither defines nor dates. */
    Set<String> unplacedContexts() {
        Set<String> unplaced = new java.util.TreeSet<>();
        for (Fact fact : facts) {
            if (!contexts.containsKey(fact.contextRef())) {
                unplaced.add(fact.contextRef());
            }
        }
        return unplaced;
    }

    boolean hasConcept(String concept) {
        return facts.stream().anyMatch(fact -> fact.concept().equals(concept));
    }

    private static final class RawContext {
        private final String id;
        private boolean dimensional;
        private String start;
        private String end;
        private String instant;

        RawContext(String id) {
            this.id = id;
        }

        /** Text can arrive in several pieces; they are joined. */
        void set(String part, String text) {
            if (text.isEmpty()) {
                return;
            }
            switch (part) {
                case "startDate" -> start = start == null ? text : start + text;
                case "endDate" -> end = end == null ? text : end + text;
                case "instant" -> instant = instant == null ? text : instant + text;
                default -> {
                }
            }
        }
    }
}
