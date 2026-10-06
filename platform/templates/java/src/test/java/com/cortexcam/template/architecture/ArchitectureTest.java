package com.cortexcam.template.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.cortexcam.template", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_is_pure =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..application..",
                            "..infrastructure..",
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.fasterxml..",
                            "org.apache.kafka..",
                            "lombok.."
                    );

    @ArchTest
    static final ArchRule application_ignores_infrastructure =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..infrastructure..",
                            "jakarta.persistence..",
                            "org.springframework.web..",
                            "org.springframework.data..",
                            "org.springframework.kafka.."
                    );

    @ArchTest
    static final ArchRule entities_only_in_persistence =
            noClasses().that().resideOutsideOfPackage("..infrastructure.adapters.out.persistence..")
                    .should().beAnnotatedWith("jakarta.persistence.Entity");
}
