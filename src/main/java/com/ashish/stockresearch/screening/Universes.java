package com.ashish.stockresearch.screening;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Loads each universe's constituents from its bundled NSE Indices file. */
@Component
public class Universes {

    private static final String HEADER = "Company Name,Industry,Symbol,Series,ISIN Code";

    /** @param nseIndustry NSE's own industry label, kept for reference; screening uses the provider's classification */
    public record Member(String symbol, String companyName, String nseIndustry) {
    }

    private final Map<Universe, List<Member>> members = new EnumMap<>(Universe.class);

    public synchronized List<Member> members(Universe universe) {
        return members.computeIfAbsent(universe, Universes::load);
    }

    private static List<Member> load(Universe universe) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(universe.resource()).getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !HEADER.equals(header.replace("﻿", "").strip())) {
                throw new IllegalStateException("%s: unexpected header '%s'".formatted(universe.resource(), header));
            }
            List<Member> list = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] columns = line.split(",", -1);
                if (columns.length != 5 || columns[2].isBlank()) {
                    throw new IllegalStateException("%s: malformed row '%s'".formatted(universe.resource(), line));
                }
                list.add(new Member(columns[2].strip(), columns[0].strip(), columns[1].strip()));
            }
            return List.copyOf(list);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read " + universe.resource(), ex);
        }
    }
}
