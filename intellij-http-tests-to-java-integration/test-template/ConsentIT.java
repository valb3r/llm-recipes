package some.package_tests;

import org.junit.jupiter.api.Test;
import some.package_tests.steps.AisGivenStage;
import some.package_tests.steps.AisThenStage;
import some.package_tests.steps.AisWhenStage;

class ConsentIT extends AbstractIntegrationTestIT<AisGivenStage, AisWhenStage, AisThenStage> {
    private static final String USER = "john.doe";
    private static final String PASSWORD_USED = "12345";
    private static final String SCA_REDIRECT = "REDIRECT";

    @Test
    void globalConsentRedirectMultiSca() {
        given().createGlobalConsent(USER, SCA_REDIRECT)
                .createAuthorizationRedirect();

        when().authenticatePsu(USER, PASSWORD_USED)
                .confirmOperation(USER);

        then().getConsentStatus()
                .getConsentObject();
    }
}
