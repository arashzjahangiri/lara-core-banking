package io.lara.accounts;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import java.util.Calendar;
import java.util.Date;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The module boundary is enforced first by the four {@code pom.xml} files — a module cannot use
 * a framework it does not depend on. These rules are the second line of defence, and they exist
 * because a build file cannot express everything: it cannot stop a domain record from growing a
 * {@code double} field, and it cannot stop someone adding a dependency to the wrong pom.
 *
 * <p>Rules written only in a README decay by month two. These fail the build instead, which is
 * what keeps the boundary real in month six when one convenient {@code @Inject} in a use case
 * starts to look reasonable.
 *
 * <p>This test lives in the bootstrap module because that is the only module whose classpath can
 * see all four layers at once. It is a near-copy of the ledger's, deliberately: each service
 * owns its own boundary, and a shared rules module would couple their builds.
 */
@AnalyzeClasses(packages = "io.lara.accounts", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String DOMAIN = "..accounts.domain..";
    private static final String APPLICATION = "..accounts.application..";

    /** Frameworks that must never appear above the infrastructure layer. */
    private static final String[] FRAMEWORK_PACKAGES = {
            "..accounts.infrastructure..",
            "..accounts.bootstrap..",
            "jakarta..",
            "io.quarkus..",
            "io.smallrye..",
            "org.hibernate..",
            "com.fasterxml.jackson..",
            "org.eclipse.microprofile.."
    };

    @ArchTest
    static final ArchRule layers_point_inward = layeredArchitecture()
            .consideringOnlyDependenciesInAnyPackage("io.lara.accounts..")
            .layer("Domain").definedBy(DOMAIN)
            .layer("Application").definedBy(APPLICATION)
            .layer("Infrastructure").definedBy("..accounts.infrastructure..")
            .layer("Bootstrap").definedBy("..accounts.bootstrap..")
            .whereLayer("Bootstrap").mayNotBeAccessedByAnyLayer()
            .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Bootstrap")
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Infrastructure", "Bootstrap")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Bootstrap")
            .because("dependencies point inward: the domain knows nothing of what surrounds it");

    /**
     * The rule that matters most. The domain and application layers stay plain Java, which is what
     * lets their tests run in milliseconds with no container and no mocking framework.
     */
    @ArchTest
    static final ArchRule domain_and_application_are_framework_free = noClasses()
            .that().resideInAnyPackage(DOMAIN, APPLICATION)
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK_PACKAGES)
            .because("Quarkus lives in infrastructure and bootstrap only");

    /**
     * Use cases are constructed with {@code new} by a producer in the bootstrap module, never
     * discovered by the container. Annotations here would tie them to a runtime.
     */
    @ArchTest
    static final ArchRule application_is_annotation_free = noClasses()
            .that().resideInAPackage(APPLICATION)
            .should().beAnnotatedWith("jakarta.enterprise.context.ApplicationScoped")
            .orShould().beAnnotatedWith("jakarta.inject.Singleton")
            .orShould().beAnnotatedWith("jakarta.transaction.Transactional")
            .allowEmptyShould(true)
            .because("use cases take their dependencies through the constructor");

    /**
     * Money is stored as integer minor units. A single {@code double} anywhere near a balance is
     * how a ledger silently loses a cent.
     */
    @ArchTest
    static final ArchRule domain_declares_no_floating_point_fields = noFields()
            .that().areDeclaredInClassesThat().resideInAPackage(DOMAIN)
            .should().haveRawType(double.class)
            .orShould().haveRawType(float.class)
            .orShould().haveRawType(Double.class)
            .orShould().haveRawType(Float.class)
            .allowEmptyShould(true)
            .because("money is integer minor units; floating point cannot represent it exactly");

    @ArchTest
    static final ArchRule domain_returns_no_floating_point = noMethods()
            .that().areDeclaredInClassesThat().resideInAPackage(DOMAIN)
            .should().haveRawReturnType(double.class)
            .orShould().haveRawReturnType(float.class)
            .orShould().haveRawReturnType(Double.class)
            .orShould().haveRawReturnType(Float.class)
            .allowEmptyShould(true)
            .because("money is integer minor units; floating point cannot represent it exactly");

    /**
     * {@code java.util.Date} is mutable, has no time zone and its month is zero-based. Value dates
     * and posting timestamps are not places to be casual about any of that.
     */
    @ArchTest
    static final ArchRule no_legacy_date_types_anywhere = noClasses()
            .should().dependOnClassesThat().belongToAnyOf(Date.class, Calendar.class)
            .because("java.time is the only date API used here");
}
