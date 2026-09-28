#!/usr/bin/env bash
# Deterministic invariant checks for docs/specs/invariants.md.
# Output lines: PASS|FAIL|REVIEW <ID> <message>. Exit 1 if any FAIL.
# Usage: bash scripts/verify-invariants.sh [--skip-tests]
# Portable to macOS bash 3.2 and BSD grep (no -P, no associative arrays).

cd "$(dirname "$0")/.." || exit 2
MAIN=src/main/java
TEST=src/test/java
fails=0
pass()   { echo "PASS   $1 $2"; }
fail()   { echo "FAIL   $1 $2"; fails=$((fails+1)); }
review() { echo "REVIEW $1 $2"; }

# INV-1 test suite
if [ "$1" = "--skip-tests" ]; then
  echo "SKIP   INV-1 tests skipped by flag"
else
  mkdir -p target
  if ./mvnw -q test > target/verify-tests.log 2>&1; then
    pass INV-1 "./mvnw test passed"
  else
    fail INV-1 "./mvnw test failed - see target/verify-tests.log"
    grep -E "Tests run:|FAIL|ERROR" target/verify-tests.log | tail -15 | sed 's/^/         /'
  fi
fi

# INV-2 newly disabled tests
added_disabled=$(git diff HEAD --unified=0 -- "$TEST" 2>/dev/null | grep -E '^\+.*@Disabled' || true)
if [ -n "$added_disabled" ]; then
  fail INV-2 "@Disabled added in working tree:"; echo "$added_disabled" | sed 's/^/         /'
else
  pass INV-2 "no @Disabled added since HEAD"
fi

# INV-3 model output that bypasses the numeric verifier
found=0
for f in $(grep -rl '\.content()' "$MAIN" 2>/dev/null); do
  if ! grep -q 'NumericClaimVerifier' "$f"; then
    review INV-3 "calls .content() without NumericClaimVerifier: $f"; found=1
  fi
done
[ $found -eq 0 ] && pass INV-3 "every .content() caller references NumericClaimVerifier"

# INV-4 system prompts without evidence rules
found=0
for f in $(grep -rlE 'defaultSystem\(|\.system\(' "$MAIN" 2>/dev/null); do
  if ! grep -q 'EVIDENCE_RULES' "$f"; then
    review INV-4 "system prompt without EVIDENCE_RULES (ok if routing/general/extraction): $f"; found=1
  fi
done
[ $found -eq 0 ] && pass INV-4 "every system-prompt class references EVIDENCE_RULES"

# INV-5 recommendation language
hits=$(grep -rniE 'price target|target price|fair value|strong buy|(^|[^a-z])(buy|sell)([^a-z]|$)|hold rating|intrinsic value' \
        "$MAIN" src/main/resources --include='*.java' --include='*.yml' --include='*.yaml' --include='*.st' --include='*.txt' 2>/dev/null \
      | grep -viE 'never|do not|don.t|no buy|give no|not |must not|without' || true)
if [ -n "$hits" ]; then
  echo "$hits" | while IFS= read -r line; do review INV-5 "recommendation wording, confirm it is a prohibition: $line"; done
else
  pass INV-5 "no recommendation wording outside prohibitions"
fi

# INV-8 every @Tool class records ToolUsage
found=0
for f in $(grep -rl '@Tool(' "$MAIN" 2>/dev/null); do
  if ! grep -q 'ToolUsage' "$f"; then fail INV-8 "@Tool class does not record ToolUsage: $f"; found=1; fi
done
[ $found -eq 0 ] && pass INV-8 "all @Tool classes record ToolUsage"

# INV-10 tests must not hit the network
found=0
for f in $(grep -rlE 'https?://(query[0-9]*\.finance\.yahoo\.com|fc\.yahoo\.com|www\.nseindia\.com|api\.bseindia\.com)' "$TEST" 2>/dev/null); do
  if ! grep -qE 'MockRestServiceServer|WireMock|MockWebServer' "$f"; then
    fail INV-10 "test uses a real host without a mock server: $f"; found=1
  fi
done
for f in $(grep -rlE 'OllamaChatModel|OllamaApi' "$TEST" 2>/dev/null); do
  if ! grep -q '@Tag("llm")' "$f"; then review INV-10 "test references Ollama without @Tag(\"llm\"): $f"; found=1; fi
done
[ $found -eq 0 ] && pass INV-10 "no unmocked network use in tests"

# INV-11 secrets
secrets=$(grep -rnE '(api[_-]?key|secret|token|password)[[:space:]]*[:=][[:space:]]*["'"'"']?[A-Za-z0-9_\-]{16,}' \
          src pom.xml 2>/dev/null | grep -v '\${' || true)
if [ -n "$secrets" ]; then fail INV-11 "possible secret:"; echo "$secrets" | sed 's/^/         /'
else pass INV-11 "no key-like literals"; fi

echo "---"
echo "FAIL count: $fails (REVIEW lines must be resolved by reading the code)"
[ $fails -eq 0 ]
