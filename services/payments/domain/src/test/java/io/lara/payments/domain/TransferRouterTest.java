package io.lara.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Currency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Routing, and the value dates that fall out of it.
 *
 * <p>The cases that matter are the boundaries: one minute either side of the cutoff, and the
 * rolls across a weekend and a TARGET2 closing day. A transfer priced on the wrong side of any of
 * those settles a day late, which is the kind of error nobody notices until a reconciliation does.
 *
 * <p>Instants are built in the calendar's own zone rather than UTC. Brussels is two hours ahead
 * of UTC in October, so "15:59 local" and "15:59Z" are different moments and only one of them is
 * the question being asked.
 */
class TransferRouterTest {

    private static final String HOME_COUNTRY = "DK";
    private static final String HOME_BANK = "0040";
    private static final long SEPA_FEE = 50L;
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalTime CUTOFF = LocalTime.of(16, 0);
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    /** An account at this bank: DK, bank code 0040. */
    private static final Iban OURS = Iban.of("DK5000400440116243");

    /** A Danish IBAN at a different bank — same country, so the bank code is what decides. */
    private static final Iban ANOTHER_DANISH_BANK = Iban.of("DK9520000123456789");

    /** A German account. */
    private static final Iban ABROAD = Iban.of("DE89370400440532013000");

    private final TransferRouter router = new TransferRouter(
            HOME_COUNTRY, HOME_BANK, SEPA_FEE, BusinessCalendar.target2(CUTOFF));

    private static Instant brussels(String localDateTime) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(localDateTime), BRUSSELS).toInstant();
    }

    @Nested
    @DisplayName("choosing the scheme")
    class Scheme {

        @Test
        void an_account_at_this_bank_is_an_internal_book_transfer() {
            assertThat(router.schemeFor(OURS)).isEqualTo(TransferScheme.INTERNAL);
        }

        /**
         * The case a country check alone would get wrong. Same country, different bank, so the
         * money still has to leave the building.
         */
        @Test
        void a_danish_account_at_another_bank_is_still_sepa() {
            assertThat(router.schemeFor(ANOTHER_DANISH_BANK))
                    .isEqualTo(TransferScheme.SEPA_CREDIT_TRANSFER);
        }

        @Test
        void a_foreign_account_is_sepa() {
            assertThat(router.schemeFor(ABROAD)).isEqualTo(TransferScheme.SEPA_CREDIT_TRANSFER);
        }
    }

    @Nested
    @DisplayName("the fee")
    class Fee {

        @Test
        void is_nothing_for_an_internal_transfer() {
            Money fee = router.feeFor(TransferScheme.INTERNAL, EUR);

            assertThat(fee.isZero()).isTrue();
            assertThat(fee.currency()).isEqualTo(EUR);
        }

        @Test
        void is_the_configured_amount_for_sepa() {
            assertThat(router.feeFor(TransferScheme.SEPA_CREDIT_TRANSFER, EUR))
                    .isEqualTo(Money.of(SEPA_FEE, "EUR"));
        }

        /** The fee follows the transfer's currency; Money refuses to mix them later anyway. */
        @Test
        void is_denominated_in_the_transfers_own_currency() {
            Money fee = router.feeFor(
                    TransferScheme.SEPA_CREDIT_TRANSFER, Currency.getInstance("DKK"));

            assertThat(fee.currency().getCurrencyCode()).isEqualTo("DKK");
            assertThat(fee.minorUnits()).isEqualTo(SEPA_FEE);
        }

        @Test
        void cannot_be_configured_negative() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TransferRouter(
                            HOME_COUNTRY, HOME_BANK, -1L, BusinessCalendar.target2(CUTOFF)))
                    .withMessageContaining("cannot be negative");
        }
    }

    @Nested
    @DisplayName("the value date of an internal transfer")
    class InternalValueDate {

        /**
         * No settlement system is involved, so the calendar has no say. Both accounts are in this
         * ledger and one entry faces the other whether or not TARGET2 is open.
         */
        @ParameterizedTest(name = "{0} settles the same day")
        @CsvSource({
                "2026-10-02T09:00:00, 2026-10-02",   // ordinary Friday morning
                "2026-10-02T23:30:00, 2026-10-02",   // long after the cutoff
                "2026-10-03T11:00:00, 2026-10-03",   // Saturday
                "2026-12-25T10:00:00, 2026-12-25"    // Christmas Day
        })
        void is_always_the_day_it_was_requested(String requestedAt, LocalDate expected) {
            assertThat(router.valueDateFor(TransferScheme.INTERNAL, brussels(requestedAt)))
                    .isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("the value date of a SEPA transfer")
    class SepaValueDate {

        @Test
        void is_today_when_it_arrives_before_the_cutoff() {
            // Thursday 1 October 2026, one minute before the 16:00 cutoff.
            assertThat(sepaValueDate("2026-10-01T15:59:00")).isEqualTo(LocalDate.of(2026, 10, 1));
        }

        @Test
        void rolls_to_tomorrow_one_minute_after_the_cutoff() {
            assertThat(sepaValueDate("2026-10-01T16:01:00")).isEqualTo(LocalDate.of(2026, 10, 2));
        }

        /** Exactly at the cutoff is already too late: the settlement run has gone. */
        @Test
        void treats_the_cutoff_instant_itself_as_too_late() {
            assertThat(sepaValueDate("2026-10-01T16:00:00")).isEqualTo(LocalDate.of(2026, 10, 2));
        }

        /**
         * The weekend roll. 2 October 2026 is a Friday, so anything after its cutoff waits until
         * Monday the 5th — the two intervening days are not business days at all.
         */
        @Test
        void rolls_across_a_weekend() {
            assertThat(sepaValueDate("2026-10-02T17:30:00")).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(sepaValueDate("2026-10-03T09:00:00")).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(sepaValueDate("2026-10-04T09:00:00")).isEqualTo(LocalDate.of(2026, 10, 5));
        }

        /**
         * The TARGET2 roll, and the reason the calendar is computed rather than tabulated.
         *
         * <p>Easter Sunday 2026 is 5 April, so Good Friday is the 3rd and Easter Monday the 6th.
         * A transfer submitted after Thursday's cutoff crosses a closing day, a weekend and
         * another closing day before it can settle on Tuesday the 7th.
         */
        @Test
        void rolls_across_the_easter_closing_days() {
            assertThat(Target2Holidays.easterSunday(2026)).isEqualTo(LocalDate.of(2026, 4, 5));

            // Thursday 2 April 2026, after the cutoff.
            assertThat(sepaValueDate("2026-04-02T18:00:00")).isEqualTo(LocalDate.of(2026, 4, 7));

            // Good Friday itself, at any hour.
            assertThat(sepaValueDate("2026-04-03T09:00:00")).isEqualTo(LocalDate.of(2026, 4, 7));

            // Easter Monday.
            assertThat(sepaValueDate("2026-04-06T09:00:00")).isEqualTo(LocalDate.of(2026, 4, 7));
        }

        /** Christmas and Boxing Day are both closing days, so the roll clears them together. */
        @Test
        void rolls_across_christmas() {
            // Thursday 24 December 2026, after the cutoff. The 25th and 26th are closed, the
            // 26th is also a Saturday, so the next business day is Monday the 28th.
            assertThat(sepaValueDate("2026-12-24T17:00:00")).isEqualTo(LocalDate.of(2026, 12, 28));
        }

        /**
         * The zone matters. 23:00 in Copenhagen and 23:00 in Tokyo are different moments in the
         * settlement day, and the answer must come from the calendar's zone rather than anyone's
         * local one.
         */
        @Test
        void is_decided_in_the_settlement_systems_own_zone() {
            // 15:30 UTC on Thursday 1 October is 17:30 in Brussels: past the cutoff.
            Instant utcBeforeCutoffLocallyAfter = Instant.parse("2026-10-01T15:30:00Z");

            assertThat(router.valueDateFor(
                    TransferScheme.SEPA_CREDIT_TRANSFER, utcBeforeCutoffLocallyAfter))
                    .isEqualTo(LocalDate.of(2026, 10, 2));
        }

        private LocalDate sepaValueDate(String localDateTime) {
            return router.valueDateFor(TransferScheme.SEPA_CREDIT_TRANSFER, brussels(localDateTime));
        }
    }

    @Nested
    @DisplayName("routing a whole transfer")
    class WholeDecision {

        @Test
        void prices_an_internal_transfer_at_nothing_and_settles_it_today() {
            RoutingDecision decision = router.route(OURS, EUR, brussels("2026-10-02T23:00:00"));

            assertThat(decision.scheme()).isEqualTo(TransferScheme.INTERNAL);
            assertThat(decision.isFree()).isTrue();
            assertThat(decision.valueDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        }

        @Test
        void prices_a_sepa_transfer_with_a_fee_and_the_next_business_day() {
            RoutingDecision decision = router.route(ABROAD, EUR, brussels("2026-10-02T23:00:00"));

            assertThat(decision.scheme()).isEqualTo(TransferScheme.SEPA_CREDIT_TRANSFER);
            assertThat(decision.fee()).isEqualTo(Money.of(SEPA_FEE, "EUR"));
            assertThat(decision.valueDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        }

        /**
         * A book transfer involves no outside party, so there is nobody to charge for. The
         * decision type refuses the combination outright rather than trusting every caller to
         * remember.
         */
        @Test
        void refuses_to_describe_an_internal_transfer_that_carries_a_fee() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RoutingDecision(
                            TransferScheme.INTERNAL, Money.of(50, "EUR"), LocalDate.of(2026, 10, 2)))
                    .withMessageContaining("cannot carry a fee");
        }

        @Test
        void refuses_a_negative_fee() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RoutingDecision(
                            TransferScheme.SEPA_CREDIT_TRANSFER,
                            Money.of(-1, "EUR"),
                            LocalDate.of(2026, 10, 2)))
                    .withMessageContaining("cannot be negative");
        }
    }

    @Nested
    @DisplayName("what the debtor actually pays")
    class TotalDebit {

        @Test
        void is_the_amount_plus_the_fee_for_a_sepa_transfer() {
            Transfer transfer = Transfer.request(
                    TransferId.newId(),
                    OURS,
                    ABROAD,
                    Money.of(125_000, "EUR"),
                    router.route(ABROAD, EUR, brussels("2026-10-01T09:00:00")),
                    "arash",
                    java.time.Clock.fixed(Instant.parse("2026-10-01T07:00:00Z"), ZoneId.of("UTC")));

            assertThat(transfer.amount()).isEqualTo(Money.of(125_000, "EUR"));
            assertThat(transfer.fee()).isEqualTo(Money.of(50, "EUR"));
            assertThat(transfer.totalDebit()).isEqualTo(Money.of(125_050, "EUR"));
        }

        @Test
        void is_just_the_amount_for_an_internal_transfer() {
            Transfer transfer = Transfer.request(
                    TransferId.newId(),
                    ABROAD,
                    OURS,
                    Money.of(125_000, "EUR"),
                    router.route(OURS, EUR, brussels("2026-10-01T09:00:00")),
                    "arash",
                    java.time.Clock.fixed(Instant.parse("2026-10-01T07:00:00Z"), ZoneId.of("UTC")));

            assertThat(transfer.totalDebit()).isEqualTo(transfer.amount());
        }

        /**
         * Money refuses to add across currencies, so without this check the mismatch would
         * surface at {@code totalDebit()} — far from the routing decision that caused it.
         */
        @Test
        void refuses_a_fee_in_a_different_currency_from_the_amount() {
            RoutingDecision mismatched = new RoutingDecision(
                    TransferScheme.SEPA_CREDIT_TRANSFER,
                    Money.of(50, "DKK"),
                    LocalDate.of(2026, 10, 2));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Transfer.request(
                            TransferId.newId(),
                            OURS,
                            ABROAD,
                            Money.of(125_000, "EUR"),
                            mismatched,
                            "arash",
                            java.time.Clock.systemUTC()))
                    .withMessageContaining("DKK")
                    .withMessageContaining("EUR");
        }
    }
}
