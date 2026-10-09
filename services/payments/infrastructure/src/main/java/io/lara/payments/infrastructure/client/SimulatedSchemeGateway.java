package io.lara.payments.infrastructure.client;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.lara.payments.application.SchemeAcknowledgement;
import io.lara.payments.application.SchemeGateway;
import io.lara.payments.application.SchemeSubmission;

/**
 * A stand-in for a real SEPA connection.
 *
 * <p><strong>This is a simulation and is labelled as one.</strong> A genuine scheme connection is
 * an ISO 20022 message over a bank-to-bank network with certificates, signing and a settlement
 * cycle measured in hours. None of that is in scope here, and faking it convincingly would be
 * worse than not faking it at all — a reader could not tell which parts were real.
 *
 * <p>What it does do is honest about the one thing that matters architecturally: a scheme can
 * decline a payment after the ledger has already moved the money, and that is the only trigger
 * compensation has. Rejections are driven by configuration rather than randomness, so the
 * compensation path is reachable in a test and reproducible in a demo.
 *
 * <p>Replacing this with a real gateway means implementing {@link SchemeGateway} and nothing
 * else. Every caller already goes through the port.
 */
@ApplicationScoped
public class SimulatedSchemeGateway implements SchemeGateway {

    private static final Logger LOG = Logger.getLogger(SimulatedSchemeGateway.class);

    /**
     * IBANs this simulated scheme refuses, comma separated.
     *
     * <p>Configuration rather than chance. A gateway that rejected at random would make the
     * compensation tests flaky and the demo unrepeatable, and "sometimes it reverses" is not a
     * demonstration of anything.
     */
    @ConfigProperty(name = "payments.scheme.rejected-ibans")
    Optional<List<String>> rejectedIbans;

    @Override
    public SchemeAcknowledgement submit(SchemeSubmission submission) {
        String creditor = submission.creditor().value().toUpperCase(Locale.ROOT);

        if (rejected().contains(creditor)) {
            LOG.infof("simulated scheme rejecting %s to %s", submission.reference(), creditor);
            return SchemeAcknowledgement.rejected("beneficiary account closed at the creditor bank");
        }

        LOG.debugf("simulated scheme accepting %s for value %s",
                submission.reference(), submission.valueDate());
        return SchemeAcknowledgement.accepted("accepted for settlement on " + submission.valueDate());
    }

    /**
     * The configured rejections, upper-cased for comparison.
     *
     * <p>{@code Optional} rather than a blank default: an empty string is not a value Quarkus
     * will convert to a {@code String}, so {@code defaultValue = ""} fails at startup with a
     * message about the property rather than about the empty default.
     */
    private Set<String> rejected() {
        return rejectedIbans.orElseGet(List::of).stream()
                .map(iban -> iban.trim().toUpperCase(Locale.ROOT))
                .filter(iban -> !iban.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
