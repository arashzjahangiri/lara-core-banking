package io.lara.payments.fakes;

import java.util.Map;
import java.util.Set;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Replaces the three outbound adapters with fakes, for this profile only.
 *
 * <p>Scoped to a profile rather than applied globally on purpose. {@code ResilienceTest} needs the
 * real adapters precisely so it can assert what happens when nothing answers them, and a global
 * alternative would quietly delete that test''s subject.
 *
 * <p>Fault tolerance stays off, as everywhere in the test profile: a breaker tripping partway
 * through a concurrency run would make later assertions fail for reasons unrelated to what they
 * are checking.
 */
public class StubbedDependenciesProfile implements QuarkusTestProfile {

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(InMemoryLedger.class, FixedAccounts.class, ScriptedRisk.class, ScriptedScheme.class);
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                // The sweep is driven explicitly by the tests. Letting a scheduler fire mid-run
                // would make "the sweep did not double-drive a step" a statement about timing.
                "quarkus.scheduler.enabled", "false",
                "quarkus.fault-tolerance.enabled", "false");
    }
}