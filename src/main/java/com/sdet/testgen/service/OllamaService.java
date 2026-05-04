package com.sdet.testgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sdet.testgen.model.TestGenRequest;
import com.sdet.testgen.model.TestGenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Slf4j
@Service
public class OllamaService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${ollama.model:llama3}")
    private String model;

    @Value("${ollama.timeout-seconds:180}")
    private int timeoutSeconds;

    public OllamaService(@Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
                         ObjectMapper objectMapper) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.objectMapper = objectMapper;
    }

    public TestGenResponse generate(TestGenRequest req) {
        String inputContext = buildInputContext(req);

        log.info("Step 1/2 — Generating .feature file from acceptance criteria");
        String gherkin = callOllama(buildGherkinPrompt(inputContext));
        if (gherkin == null) {
            return TestGenResponse.error("Failed to reach Ollama. Run: ollama serve");
        }

        log.info("Step 2/2 — Generating Step Definitions");
        String stepDefs = callOllama(buildStepDefsPrompt(inputContext, gherkin));
        if (stepDefs == null) {
            stepDefs = "// Step definition generation failed. Re-run to retry.";
        }

        long count = gherkin.lines().filter(l -> l.trim().startsWith("Scenario")).count();
        return TestGenResponse.success(gherkin.trim(), stepDefs.trim(), (int) count);
    }

    private String callOllama(String prompt) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);
            ObjectNode options = objectMapper.createObjectNode();
            options.put("temperature", 0.1);
            options.put("num_predict", 4096);
            body.set("options", options);

            String raw = webClient.post()
                    .uri("/api/generate")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            JsonNode root = objectMapper.readTree(raw);
            String text = root.path("response").asText("").trim();
            text = text.replaceAll("(?s)```[a-zA-Z]*\\s*", "").replaceAll("```", "").trim();
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            log.error("Ollama call failed: {}", e.getMessage(), e);
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // PROMPT 1 — Feature file
    // One Scenario per acceptance criterion ONLY. No extra scenarios.
    // ════════════════════════════════════════════════════════════════
    private String buildGherkinPrompt(String inputContext) {
        return """
                You are a senior SDET writing Cucumber BDD feature files.
                Output ONLY the content of a .feature file.
                No explanation, no markdown, no code fences. Start directly with Feature:

                Requirements and API details:
                %s

                STRICT RULES — follow every rule exactly:
                1. Write ONLY the Scenarios that are explicitly stated in the acceptance criteria.
                   Do NOT invent extra scenarios. Do NOT add generic positive/negative/edge cases.
                2. One Scenario per acceptance criterion. Number them TC_01, TC_02, etc. in scenario names.
                3. First step of EVERY Scenario must be:
                     Given I authenticate and set the API token
                4. For POST/PUT/PATCH requests — NEVER put JSON in the feature file.
                   Instead use this step pattern (replace TC_01 with the actual TC number):
                     When I send a POST request to "<endpoint>" using test data "TC_01"
                   The test data file src/test/resources/testdata/<FeatureName>/TC_01_request.json will hold the body.
                5. For GET/DELETE requests (no body):
                     When I send a GET request to "<endpoint>"
                6. Then steps must verify the HTTP status code AND specific response fields from the AC.
                   Use these step patterns for assertions:
                     Then the response status code should be <code>
                     And the response field "<fieldPath>" should equal "<expectedValue>"
                     And the response field "<fieldPath>" should not be null
                7. Use dot notation for nested fields: "data.color", "data.year", "user.address.city"
                8. Use exact values from the acceptance criteria in assertion steps.
                9. Do NOT put JSON bodies anywhere in the feature file.

                Output the .feature file now:
                """.formatted(inputContext);
    }

    // ════════════════════════════════════════════════════════════════
    // PROMPT 2 — Step Definitions
    // x-api-key header. Full imports. No TestNG. No TODOs. JUnit only.
    // ════════════════════════════════════════════════════════════════
    private String buildStepDefsPrompt(String inputContext, String gherkin) {
        return """
                You are a senior SDET. Output ONLY a complete, fully implemented Java Cucumber Step Definitions class.
                No explanation, no markdown, no code fences.
                Start directly with: package stepDefinitions;
                NEVER write // TODO, placeholder comments, or empty method bodies.
                Every method body must contain real, working RestAssured or assertion code.

                API Details:
                %s

                Feature file to implement:
                %s

                ══ MANDATORY IMPORTS — include ALL of these, no exceptions ═════════════

                import common.restUtils;
                import io.cucumber.java.Before;
                import io.cucumber.java.en.And;
                import io.cucumber.java.en.Given;
                import io.cucumber.java.en.Then;
                import io.cucumber.java.en.When;
                import io.restassured.RestAssured;
                import io.restassured.response.Response;
                import io.restassured.http.ContentType;
                import io.restassured.specification.RequestSpecification;
                import static io.restassured.RestAssured.given;
                import static org.hamcrest.MatcherAssert.assertThat;
                import static org.hamcrest.Matchers.*;
                import org.junit.Assert;
                import java.util.List;
                import java.util.Map;
                import java.util.HashMap;
                import java.util.ArrayList;

                ══ MANDATORY CLASS STRUCTURE ══════════════════════════════════════════

                public class ApiStepDefinitions {

                    private String apiToken;
                    private Response response;
                    private String requestBody;
                    private String endpointPath;
                    private RequestSpecification requestSpec;

                    @Before
                    public void setup() {
                        RestAssured.baseURI = "<base URL from API details>";
                        apiToken = new restUtils().getApiToken();
                        requestSpec = given()
                            .header("x-api-key", apiToken)
                            .contentType(ContentType.JSON);
                    }

                    @Given("I authenticate and set the API token")
                    public void authenticateAndSetToken() {
                        apiToken = new restUtils().getApiToken();
                        requestSpec = given()
                            .header("x-api-key", apiToken)
                            .contentType(ContentType.JSON);
                    }
                }

                ══ IMPLEMENTATION RULES ═══════════════════════════════════════════════

                RULE 1 — Auth header:
                  ALWAYS use .header("x-api-key", apiToken) via requestSpec.
                  NEVER use .auth().oauth2() or Authorization Bearer.

                RULE 2 — Parameter count must match step captures exactly:
                  @When("I send GET to {string} with id {int}")
                  public void sendGet(String path, int id) { ... }  // 2 captures = 2 params ✓

                RULE 3 — HTTP calls always via requestSpec:
                  // GET:
                  response = requestSpec.when().get(endpointPath);
                  // POST/PUT:
                  response = requestSpec.body(requestBody).when().post(endpointPath);
                  // DELETE:
                  response = requestSpec.when().delete(endpointPath);

                RULE 4 — Store state between steps in instance fields:
                  @Given("I set the request body to {string}")
                  public void setRequestBody(String body) { this.requestBody = body; }
                  @When("I send a POST request to {string}")
                  public void sendPost(String path) {
                      this.endpointPath = path;
                      response = requestSpec.body(requestBody).when().post(path);
                  }

                RULE 5 — Assertions use response field only, NEVER RestAssured.lastResponse():
                  Assert.assertEquals(expectedCode, response.getStatusCode());
                  assertThat(response.jsonPath().getString("fieldName"), equalTo("expected"));
                  assertThat(response.jsonPath().get("field"), is(notNullValue()));
                  List<Object> list = response.jsonPath().getList("$");
                  assertThat(list, is(not(empty())));

                RULE 6 — No TestNG. Only: org.junit.Assert, io.cucumber.java.en.*, io.cucumber.java.Before.

                RULE 7 — If a step uses path params, build the URL inline:
                  String fullPath = "/api/v1/products/" + productId;
                  response = requestSpec.when().get(fullPath);

                ══ NOW GENERATE ═══════════════════════════════════════════════════════

                Implement EVERY step from the feature file. No skips. No TODOs. Real code only.

                Output the complete Java class now:
                """.formatted(inputContext, gherkin);
    }

    private String buildInputContext(TestGenRequest req) {
        return switch (req.getInputMode() != null ? req.getInputMode() : "endpoint") {
            case "swagger"      -> "OpenAPI/Swagger JSON:\n" + req.getSwaggerJson();
            case "rawjson"      -> "Request JSON:\n" + req.getRequestJson()
                                 + "\n\nResponse JSON:\n" + req.getResponseJson();
            case "requirements" -> req.getApiDescription();
            default             -> "HTTP Method: " + req.getHttpMethod()
                                 + "\nEndpoint URL: " + req.getEndpointUrl()
                                 + "\nDescription: " + req.getApiDescription();
        };
    }
}
