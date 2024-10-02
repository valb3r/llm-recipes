package some.package_tests.steps;

import com.tngtech.jgiven.integration.spring.JGivenStage;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import java.util.Map;
import java.util.UUID;

@JGivenStage
public class AisGivenStage extends Stage {
    private static final String CREATE_AUTHORIZATION = openBankingV2("/consents/{consent_id}/authorisations");

    @SneakyThrows
    public AisGivenStage createGlobalConsent(String psuId, String scaApproach) {
        String body = """
                {
                  "access": {
                  },
                  "consentType": "global",
                  "recurringIndicator": false,
                  "validTo": "2034-01-09",
                  "frequencyPerDay": 4
                }
                """;
        Response response = RestAssured.given()
                                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                                    .body(body)
                                    .when()
                                    .post(openBankingV2("/consents/account-access"));

        response.then().statusCode(HttpStatus.CREATED.value());

        return self();
    }

    public AisGivenStage createAuthorizationRedirect() {
        Response response = RestAssured.given()
                                    .accept(MediaType.APPLICATION_JSON_VALUE)
                                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                                    .when()
                                    .post(CREATE_AUTHORIZATION, this.consentId);
        response.then().statusCode(HttpStatus.CREATED.value());

        return self();
    }
}
