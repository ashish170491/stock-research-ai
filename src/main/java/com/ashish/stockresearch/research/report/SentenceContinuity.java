package com.ashish.stockresearch.research.report;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps model text coherent after a verifier removes a sentence. The next sentence can be left
 * stranded: "However, ..." now contrasts with nothing, and "These figures ..." points at figures
 * that are no longer there.
 */
final class SentenceContinuity {

    /** A discourse connective opening a sentence. The comma is required, so "Still waiting" is not one. */
    private static final Pattern CONNECTIVE = Pattern.compile(
            "^(\\s*)(?:however|additionally|also|furthermore|moreover|similarly|likewise|in addition|nevertheless|"
                    + "nonetheless|still|that said|meanwhile|conversely|in contrast|by contrast|on the other hand|"
                    + "as a result|therefore|thus|consequently|hence),\\s+(\\p{L})",
            Pattern.CASE_INSENSITIVE);

    /** A sentence whose subject is the figures of the sentence before it. */
    private static final Pattern REFERS_BACK = Pattern.compile(
            "^\\s*(?:(?:however|but|yet),?\\s+)?(?:these|those|this|that|such)\\s+"
                    + "(?:figures?|values?|numbers?|metrics?|ratios?|results?)\\b",
            Pattern.CASE_INSENSITIVE);

    private SentenceContinuity() {
    }

    /** True when the sentence is about the figures of the sentence before it. */
    static boolean refersBack(String sentence) {
        return REFERS_BACK.matcher(sentence).find();
    }

    /**
     * The sentence without the connective that tied it to a removed sentence. Only the connective
     * goes, so no claim is added or changed.
     */
    static String detached(String sentence) {
        Matcher matcher = CONNECTIVE.matcher(sentence);
        if (!matcher.find()) {
            return sentence;
        }
        return matcher.group(1) + matcher.group(2).toUpperCase() + sentence.substring(matcher.end());
    }
}
