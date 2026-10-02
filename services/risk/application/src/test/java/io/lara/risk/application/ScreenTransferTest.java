package io.lara.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.risk.domain.CustomerId;
import io.lara.risk.domain.CustomerLimits;
import io.lara.risk.domain.Money;
import io.lara.risk.domain.PartyName;
import io.lara.risk.domain.RecordedScreening;
import io.lara.risk.domain.SanctionedParty;
import io.lara.risk.domain.SanctionsList;
import io.lara.risk.domain.ScreeningOutcome;
import io.lara.risk.domain.ScreeningReference;
import io.lara.risk.domain.ScreeningRequest;
import io.lara.risk.domain.TransferScreening;

/**
 * The use case, driven with in-memory ports and no container.
 *
 * <p>What is tested here is sequencing rather than screening logic — the rules themselves have
 * their own test. The two things that can only go wrong at this level are recording a decision
 * after returning it, and re-evaluating a question that has already been answered.
 */
class ScreenTransferTest {

    private static final CustomerId CUSTOMER = CustomerId.of(UUID.randomUUID());
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    private final InMemoryLimits limits = new InMemoryLimits();
    private final InMemoryTotals totals = new InMemoryTotals();
    private final RecordingDecisions decisions = new RecordingDecisions();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-10-02T09:00:00Z"));

    private ScreenTransfer useCase(SanctionsList sanctions) {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        return new ScreenTransfer(
                new TransferScreening(),
                () -> sanctions,
                limits,
                totals,
                decisions,
                Money.of(1_000_000, "EUR"),
                Money.of(500_000, "EUR"),
                BRUSSELS,
                clock);
    }

    private ScreenTransfer useCase() {
        return useCase(SanctionsList.empty());
    }

    private static ScreeningRequest request(String reference, long minorUnits, String beneficiary) {
        return new ScreeningRequest(
                ScreeningReference.of(reference),
                CUSTOMER,
                Money.of(minorUnits, "EUR"),
                PartyName.of(beneficiary));
    }

    @Nested
    @DisplayName("deciding")
    class Deciding {

        @Test
        void records_the_decision_before_handing_it_back() {
            RecordedScreening result = useCase().screen(request("SCR-1", 10_000, "Jens Hansen"));

            // Returned and stored are the same fact. A caller holding a decision this service
            // has no record of is the failure this ordering exists to prevent.
            assertThat(decisions.recorded).containsExactly(result);
            assertThat(result.outcome()).isEqualTo(ScreeningOutcome.ALLOW);
        }

        @Test
        void keeps_the_inputs_alongside_the_answer() {
            RecordedScreening result = useCase().screen(request("SCR-2", 10_000, "Jens Hansen"));

            assertThat(result.customer()).isEqualTo(CUSTOMER);
            assertThat(result.amount()).isEqualTo(Money.of(10_000, "EUR"));
            assertThat(result.beneficiary().original()).isEqualTo("Jens Hansen");
            assertThat(result.screenedAt()).isEqualTo(now.get());
        }

        @Test
        void applies_a_sanctions_match() {
            SanctionsList list = new SanctionsList(
                    List.of(SanctionedParty.of("Ivan Petrov", "UN-1267/8821")));

            RecordedScreening result = useCase(list).screen(request("SCR-3", 100, "Ivan Petrov"));

            assertThat(result.outcome()).isEqualTo(ScreeningOutcome.BLOCK);
        }
    }

    @Nested
    @DisplayName("asking twice")
    class Idempotency {

        /**
         * Payments retries a timed-out screening rather than guessing. Limits move during the
         * day, so a re-evaluated retry could allow at 14:02 what it blocked at 14:01.
         */
        @Test
        void returns_the_stored_decision_without_re_evaluating() {
            ScreenTransfer screen = useCase();
            ScreeningRequest first = request("SCR-SAME", 10_000, "Jens Hansen");

            RecordedScreening original = screen.screen(first);

            // The world changes underneath: the customer has now used up their day.
            totals.set(LocalDate.of(2026, 10, 2), Money.of(1_000_000, "EUR"));
            now.set(Instant.parse("2026-10-02T14:00:00Z"));

            RecordedScreening replay = screen.screen(request("SCR-SAME", 10_000, "Jens Hansen"));

            assertThat(replay).isEqualTo(original);
            assertThat(replay.screenedAt()).isEqualTo(original.screenedAt());
            assertThat(decisions.recorded).hasSize(1);
        }

        @Test
        void treats_a_different_reference_as_a_new_question() {
            ScreenTransfer screen = useCase();

            screen.screen(request("SCR-A", 10_000, "Jens Hansen"));
            screen.screen(request("SCR-B", 10_000, "Jens Hansen"));

            assertThat(decisions.recorded).hasSize(2);
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {

        /**
         * Most customers have no row of their own. Failing on a missing one would break every
         * new customer's first payment; defaulting to no limit at all would be far worse.
         */
        @Test
        void fall_back_to_the_service_default_when_the_customer_has_none() {
            RecordedScreening result = useCase().screen(request("SCR-4", 1_000_001, "Jens Hansen"));

            assertThat(result.outcome()).isEqualTo(ScreeningOutcome.BLOCK);
            assertThat(result.decision().reason()).contains("daily limit");
        }

        @Test
        void use_the_customers_own_row_when_there_is_one() {
            limits.set(new CustomerLimits(CUSTOMER, Money.of(500, "EUR"), Money.of(100, "EUR")));

            RecordedScreening result = useCase().screen(request("SCR-5", 501, "Jens Hansen"));

            assertThat(result.outcome()).isEqualTo(ScreeningOutcome.BLOCK);
        }

        @Test
        void count_what_was_already_screened_on_the_same_day() {
            totals.set(LocalDate.of(2026, 10, 2), Money.of(999_999, "EUR"));

            RecordedScreening result = useCase().screen(request("SCR-6", 2, "Jens Hansen"));

            assertThat(result.outcome()).isEqualTo(ScreeningOutcome.BLOCK);
        }

        /**
         * The day is the business day in the configured zone, not UTC. 23:30 UTC on 2 October is
         * already 01:30 on the 3rd in Brussels, so the previous day's total must not follow it
         * across.
         */
        @Test
        void start_again_on_the_next_business_day_in_the_configured_zone() {
            totals.set(LocalDate.of(2026, 10, 2), Money.of(1_000_000, "EUR"));
            now.set(Instant.parse("2026-10-02T23:30:00Z"));

            RecordedScreening result = useCase().screen(request("SCR-7", 10_000, "Jens Hansen"));

            assertThat(totals.lastQueriedDay).isEqualTo(LocalDate.of(2026, 10, 3));
            assertThat(result.outcome()).isEqualTo(ScreeningOutcome.ALLOW);
        }
    }

    @Nested
    @DisplayName("what counts towards the daily total")
    class Counting {

        /**
         * A blocked attempt moved no money. Counting it would let a customer exhaust their own
         * limit with refusals and lock out the legitimate payment that followed.
         */
        @Test
        void excludes_blocked_screenings() {
            SanctionsList list = new SanctionsList(
                    List.of(SanctionedParty.of("Ivan Petrov", "UN-1267/8821")));
            RecordedScreening blocked = useCase(list).screen(request("SCR-8", 100, "Ivan Petrov"));

            assertThat(blocked.countsTowardsTheDailyTotal()).isFalse();
        }

        @Test
        void includes_allowed_and_reviewed_screenings() {
            RecordedScreening allowed = useCase().screen(request("SCR-9", 100, "Jens Hansen"));
            RecordedScreening reviewed = useCase().screen(request("SCR-10", 500_001, "Jens Hansen"));

            assertThat(allowed.countsTowardsTheDailyTotal()).isTrue();
            assertThat(reviewed.outcome()).isEqualTo(ScreeningOutcome.REVIEW);
            assertThat(reviewed.countsTowardsTheDailyTotal()).isTrue();
        }
    }

    // ------------------------------------------------------------------ in-memory ports

    private static final class InMemoryLimits implements CustomerLimitsDirectory {

        private final Map<CustomerId, CustomerLimits> rows = new HashMap<>();

        void set(CustomerLimits limits) {
            rows.put(limits.customer(), limits);
        }

        @Override
        public Optional<CustomerLimits> forCustomer(CustomerId customer) {
            return Optional.ofNullable(rows.get(customer));
        }
    }

    private static final class InMemoryTotals implements ScreenedTotals {

        private final Map<LocalDate, Money> byDay = new HashMap<>();
        private LocalDate lastQueriedDay;

        void set(LocalDate day, Money total) {
            byDay.put(day, total);
        }

        @Override
        public Money totalFor(CustomerId customer, LocalDate day, Currency currency) {
            lastQueriedDay = day;
            return byDay.getOrDefault(day, Money.zero(currency));
        }
    }

    private static final class RecordingDecisions implements ScreeningDecisions {

        private final List<RecordedScreening> recorded = new ArrayList<>();

        @Override
        public Optional<RecordedScreening> findByReference(ScreeningReference reference) {
            return recorded.stream()
                    .filter(screening -> screening.reference().equals(reference))
                    .findFirst();
        }

        @Override
        public void record(RecordedScreening screening) {
            recorded.add(screening);
        }
    }
}
