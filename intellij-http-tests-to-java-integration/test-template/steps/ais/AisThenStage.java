package some.package_tests.steps;

import com.tngtech.jgiven.integration.spring.JGivenStage;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JGivenStage
public class AisThenStage extends Stage {
    private static final String CONSENT_STATUS = openBankingV2("consents/account-access/{consent_id}/status");
    private static final String CONSENT_OBJECT = openBankingV2("/consents/account-access/{consent_id}");

    public AisThenStage getConsentStatus() {
        Response response = RestAssured.given()
                                    .accept(MediaType.APPLICATION_JSON_VALUE)
                                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                                    .when()
                                    .get(CONSENT_STATUS, this.consentId);
        response.then().statusCode(HttpStatus.OK.value());
        return self();
    }

    public AisThenStage getConsentObject() {
        Response response = RestAssured.given()
                                    .accept(MediaType.APPLICATION_JSON_VALUE)
                                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                                    .when()
                                    .get(CONSENT_OBJECT, this.consentId);
        response.then().statusCode(HttpStatus.OK.value());
        return self();
    }
}
