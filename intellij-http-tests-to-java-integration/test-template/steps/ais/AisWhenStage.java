package some.package_tests.steps;

import com.tngtech.jgiven.integration.spring.JGivenStage;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import java.util.Map;
import java.util.UUID;
import static org.springframework.http.HttpHeaders.COOKIE;

@JGivenStage
public class AisWhenStage extends Stage {

    private static final String AUTHENTICATE_PSU_URL = openBankingV2("/consents/{consent_id}/authorisations" +
                                                                             "/{authorisation_id}");

    public AisWhenStage authenticatePsu(String psuId, String psuPass) {
        String body = """
                {"psuData": {
                      "password": "%s"
                }}
                """.formatted(psuPass);
        Response response = RestAssured.given()
                                    .accept(MediaType.APPLICATION_JSON_VALUE)
                                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                                    .body(body)
                                    .when()
                                    .put(openBankingV2("/consents/{consent_id}/authorisations/{authorisation_id}"),
                                         this.consentId, this.authorizationId);
        response.then().statusCode(HttpStatus.OK.value());
        return self();
    }

    public AisWhenStage confirmOperation(String psuId) {
        String body = """
                {"psuData": {}}
                """;
        Response response = RestAssured.given()
                                    .accept(MediaType.APPLICATION_JSON_VALUE)
                                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                                    .body(body)
                                    .when()
                                    .put(AUTHENTICATE_PSU_URL, this.consentId, this.authorizationId);
        response.then().statusCode(HttpStatus.OK.value());
        return self();
    }
}
