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

    public OllamaService(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            ObjectMapper objectMapper) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────
    public TestGenResponse generate(TestGenRequest req) {
        String ctx         = buildInputContext(req);
        String featureName = req.getFeatureName() != null ? req.getFeatureName() : "GeneratedApi";

        log.info("Step 1/2 — Generating .feature file");
        String gherkin = callOllama(gherkinPrompt(ctx, featureName));
        if (gherkin == null) {
            return TestGenResponse.error("Failed to reach Ollama. Run: ollama serve");
        }

        log.info("Step 2/2 — Generating Step Definitions");
        String stepDefs = callOllama(stepDefsPrompt(ctx, gherkin, featureName));
        if (stepDefs == null) {
            stepDefs = "// Step definition generation failed. Re-run to retry.";
        }

        long count = gherkin.lines().filter(l -> l.trim().startsWith("Scenario")).count();
        return TestGenResponse.success(gherkin.trim(), stepDefs.trim(), (int) count);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ollama HTTP call
    // ─────────────────────────────────────────────────────────────────────────
    private String callOllama(String prompt) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);
            ObjectNode opts = objectMapper.createObjectNode();
            opts.put("temperature", 0.1);
            opts.put("num_predict", 4096);
            body.set("options", opts);

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

    // ─────────────────────────────────────────────────────────────────────────
    // PROMPT 1 — Gherkin .feature file
    // Rules: AC-only, TC_XX data file refs, @api tag, no inline JSON
    // ─────────────────────────────────────────────────────────────────────────
    private String gherkinPrompt(String ctx, String featureName) {
        // Build prompt via concatenation — avoids any %s / formatted() conflicts
        return "You are a senior SDET writing Cucumber BDD feature files for an enterprise API test suite.\n"
             + "Output ONLY the raw content of a .feature file.\n"
             + "No explanation, no markdown fences. Start directly with @api on the first line.\n\n"
             + "Requirements and API details:\n"
             + ctx + "\n\n"
             + "STRICT RULES:\n\n"
             + "RULE 1 — Write ONLY the Scenarios stated in the acceptance criteria.\n"
             + "  Do NOT invent extra scenarios. One Scenario per acceptance criterion.\n\n"
             + "RULE 2 — Scenario naming:\n"
             + "  @tc_01\n"
             + "  Scenario: [TC_01] <Short description of what is being validated>\n\n"
             + "RULE 3 — First step of every Scenario must be:\n"
             + "  Given I authenticate and set the API token\n\n"
             + "RULE 4 — POST/PUT/PATCH request body via test data file (NEVER inline JSON):\n"
             + "  When I send a POST request to \"/your/endpoint\" using test data \"TC_01\"\n"
             + "  The JSON body is stored in: src/test/resources/testdata/" + featureName + "/TC_01_request.json\n\n"
             + "RULE 5 — GET/DELETE (no body):\n"
             + "  When I send a GET request to \"/your/endpoint\"\n\n"
             + "RULE 6 — Response validation with expected JSON file (when provided):\n"
             + "  And I validate the response against expected data \"TC_01\"\n"
             + "  The expected JSON is stored in: src/test/resources/testdata/" + featureName + "/TC_01_response.json\n\n"
             + "RULE 7 — Field assertion steps (use exact values from the AC):\n"
             + "  Then the response status code should be 200\n"
             + "  And the response field \"data.color\" should equal \"silver\"\n"
             + "  And the response field \"id\" should not be null\n\n"
             + "RULE 8 — Use dot notation for nested fields: \"data.color\", \"user.address.city\"\n\n"
             + "RULE 9 — Do NOT put any JSON anywhere in the feature file.\n\n"
             + "Output the .feature file now:";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROMPT 2 — Step Definitions
    // Rules: x-api-key, Jackson for JSON files, logging, Javadoc, JUnit only
    // ─────────────────────────────────────────────────────────────────────────
    private String stepDefsPrompt(String ctx, String gherkin, String featureName) {
        return "You are a senior SDET. Output ONLY a complete, fully implemented Java Cucumber Step Definitions class.\n"
             + "No explanation, no markdown, no code fences. Start directly with: package stepDefinitions;\n"
             + "NEVER write // TODO, empty method bodies, or placeholder comments.\n"
             + "Every method body must contain real, working code.\n\n"
             + "=== API DETAILS ===\n"
             + ctx + "\n\n"
             + "=== FEATURE FILE TO IMPLEMENT ===\n"
             + gherkin + "\n\n"
             + "=== FEATURE NAME (used in file paths) ===\n"
             + featureName + "\n\n"
             + "=== MANDATORY IMPORTS — include ALL ===\n"
             + "import common.restUtils;\n"
             + "import com.fasterxml.jackson.databind.ObjectMapper;\n"
             + "import com.fasterxml.jackson.databind.JsonNode;\n"
             + "import io.cucumber.java.Before;\n"
             + "import io.cucumber.java.en.And;\n"
             + "import io.cucumber.java.en.Given;\n"
             + "import io.cucumber.java.en.Then;\n"
             + "import io.cucumber.java.en.When;\n"
             + "import io.restassured.RestAssured;\n"
             + "import io.restassured.response.Response;\n"
             + "import io.restassured.http.ContentType;\n"
             + "import io.restassured.specification.RequestSpecification;\n"
             + "import static io.restassured.RestAssured.given;\n"
             + "import static org.hamcrest.MatcherAssert.assertThat;\n"
             + "import static org.hamcrest.Matchers.*;\n"
             + "import org.junit.Assert;\n"
             + "import java.util.List;\n"
             + "import java.util.Map;\n"
             + "import java.util.HashMap;\n"
             + "import java.util.ArrayList;\n"
             + "import java.util.Iterator;\n"
             + "import java.io.InputStream;\n"
             + "import java.io.IOException;\n\n"
             + "=== MANDATORY FIELDS ===\n"
             + "private String apiToken;\n"
             + "private Response response;\n"
             + "private String requestBody;\n"
             + "private String endpointPath;\n"
             + "private RequestSpecification requestSpec;\n"
             + "private static final ObjectMapper MAPPER = new ObjectMapper();\n\n"
             + "=== MANDATORY HELPER METHODS (include verbatim) ===\n\n"
             + "/** Resolves base URL from -Denv system property (dev/qa/staging/prod). */\n"
             + "private String resolveBaseUrl() {\n"
             + "    String env = System.getProperty(\"env\", \"dev\").toLowerCase();\n"
             + "    return switch (env) {\n"
             + "        case \"qa\"      -> \"https://qa-api.example.com\";\n"
             + "        case \"staging\" -> \"https://staging-api.example.com\";\n"
             + "        case \"prod\"    -> \"https://api.example.com\";\n"
             + "        default        -> \"https://dev-api.example.com\";\n"
             + "    };\n"
             + "}\n\n"
             + "/**\n"
             + " * Loads a JSON file from src/test/resources/testdata/" + featureName + "/\n"
             + " * @param tcNumber e.g. TC_01\n"
             + " * @param type     'request' or 'response'\n"
             + " * @return parsed JsonNode\n"
             + " */\n"
             + "private JsonNode loadJson(String tcNumber, String type) {\n"
             + "    String path = \"/testdata/" + featureName + "/\" + tcNumber + \"_\" + type + \".json\";\n"
             + "    try (InputStream is = getClass().getResourceAsStream(path)) {\n"
             + "        if (is == null) throw new RuntimeException(\"File not found on classpath: \" + path);\n"
             + "        return MAPPER.readTree(is);\n"
             + "    } catch (IOException e) {\n"
             + "        throw new RuntimeException(\"Failed to load \" + path + \": \" + e.getMessage());\n"
             + "    }\n"
             + "}\n\n"
             + "=== MANDATORY STEP METHODS (include ALL of these verbatim) ===\n\n"
             + "@Before\n"
             + "public void setup() {\n"
             + "    RestAssured.baseURI = resolveBaseUrl();\n"
             + "    apiToken = new restUtils().getApiToken();\n"
             + "    requestSpec = given().header(\"x-api-key\", apiToken).contentType(ContentType.JSON);\n"
             + "    System.out.println(\"[SETUP] env=\" + System.getProperty(\"env\",\"dev\") + \" baseURI=\" + RestAssured.baseURI);\n"
             + "}\n\n"
             + "@Given(\"I authenticate and set the API token\")\n"
             + "public void authenticateAndSetToken() {\n"
             + "    apiToken = new restUtils().getApiToken();\n"
             + "    requestSpec = given().header(\"x-api-key\", apiToken).contentType(ContentType.JSON);\n"
             + "    System.out.println(\"[AUTH] x-api-key acquired: \" + (apiToken != null ? \"OK\" : \"FAILED\"));\n"
             + "}\n\n"
             + "// POST with TC data file\n"
             + "@When(\"I send a POST request to {string} using test data {string}\")\n"
             + "public void sendPostWithTestData(String path, String tcNumber) {\n"
             + "    this.endpointPath = path;\n"
             + "    try { this.requestBody = MAPPER.writeValueAsString(loadJson(tcNumber, \"request\")); }\n"
             + "    catch (Exception e) { throw new RuntimeException(e); }\n"
             + "    System.out.println(\"[REQUEST] POST \" + RestAssured.baseURI + path);\n"
             + "    System.out.println(\"[REQUEST BODY] \" + requestBody);\n"
             + "    response = requestSpec.body(requestBody).when().post(path);\n"
             + "    System.out.println(\"[RESPONSE] Status: \" + response.getStatusCode());\n"
             + "    System.out.println(\"[RESPONSE BODY] \" + response.asPrettyString());\n"
             + "}\n\n"
             + "// PUT with TC data file\n"
             + "@When(\"I send a PUT request to {string} using test data {string}\")\n"
             + "public void sendPutWithTestData(String path, String tcNumber) {\n"
             + "    this.endpointPath = path;\n"
             + "    try { this.requestBody = MAPPER.writeValueAsString(loadJson(tcNumber, \"request\")); }\n"
             + "    catch (Exception e) { throw new RuntimeException(e); }\n"
             + "    System.out.println(\"[REQUEST] PUT \" + RestAssured.baseURI + path);\n"
             + "    System.out.println(\"[REQUEST BODY] \" + requestBody);\n"
             + "    response = requestSpec.body(requestBody).when().put(path);\n"
             + "    System.out.println(\"[RESPONSE] Status: \" + response.getStatusCode());\n"
             + "    System.out.println(\"[RESPONSE BODY] \" + response.asPrettyString());\n"
             + "}\n\n"
             + "// GET\n"
             + "@When(\"I send a GET request to {string}\")\n"
             + "public void sendGetRequest(String path) {\n"
             + "    this.endpointPath = path;\n"
             + "    System.out.println(\"[REQUEST] GET \" + RestAssured.baseURI + path);\n"
             + "    response = requestSpec.when().get(path);\n"
             + "    System.out.println(\"[RESPONSE] Status: \" + response.getStatusCode());\n"
             + "    System.out.println(\"[RESPONSE BODY] \" + response.asPrettyString());\n"
             + "}\n\n"
             + "// DELETE\n"
             + "@When(\"I send a DELETE request to {string}\")\n"
             + "public void sendDeleteRequest(String path) {\n"
             + "    this.endpointPath = path;\n"
             + "    System.out.println(\"[REQUEST] DELETE \" + RestAssured.baseURI + path);\n"
             + "    response = requestSpec.when().delete(path);\n"
             + "    System.out.println(\"[RESPONSE] Status: \" + response.getStatusCode());\n"
             + "    System.out.println(\"[RESPONSE BODY] \" + response.asPrettyString());\n"
             + "}\n\n"
             + "// Status code assertion\n"
             + "@Then(\"the response status code should be {int}\")\n"
             + "public void verifyStatusCode(int expected) {\n"
             + "    System.out.println(\"[ASSERT] Status expected=\" + expected + \" actual=\" + response.getStatusCode());\n"
             + "    Assert.assertEquals(\"Status code mismatch\", expected, response.getStatusCode());\n"
             + "}\n\n"
             + "// Field equals assertion\n"
             + "@And(\"the response field {string} should equal {string}\")\n"
             + "public void verifyFieldEquals(String fieldPath, String expectedValue) {\n"
             + "    Object actual = response.jsonPath().get(fieldPath);\n"
             + "    System.out.println(\"[ASSERT] '\" + fieldPath + \"' expected='\" + expectedValue + \"' actual='\" + actual + \"'\");\n"
             + "    Assert.assertEquals(\"Field mismatch: \" + fieldPath, expectedValue, String.valueOf(actual));\n"
             + "}\n\n"
             + "// Field not null assertion\n"
             + "@And(\"the response field {string} should not be null\")\n"
             + "public void verifyFieldNotNull(String fieldPath) {\n"
             + "    Object actual = response.jsonPath().get(fieldPath);\n"
             + "    System.out.println(\"[ASSERT] '\" + fieldPath + \"' not null: \" + actual);\n"
             + "    Assert.assertNotNull(\"Expected '\" + fieldPath + \"' to not be null\", actual);\n"
             + "}\n\n"
             + "// Full response JSON validation against TC_XX_response.json\n"
             + "@And(\"I validate the response against expected data {string}\")\n"
             + "public void validateResponseAgainstFile(String tcNumber) {\n"
             + "    JsonNode expected = loadJson(tcNumber, \"response\");\n"
             + "    JsonNode actual;\n"
             + "    try { actual = MAPPER.readTree(response.asString()); }\n"
             + "    catch (Exception e) { throw new RuntimeException(\"Failed to parse response: \" + e.getMessage()); }\n"
             + "    System.out.println(\"[ASSERT] Validating response against \" + tcNumber + \"_response.json\");\n"
             + "    validateJsonFields(expected, actual, \"\");\n"
             + "}\n\n"
             + "/** Recursively validates each field in expected JSON against actual response. */\n"
             + "private void validateJsonFields(JsonNode expected, JsonNode actual, String path) {\n"
             + "    Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();\n"
             + "    while (fields.hasNext()) {\n"
             + "        Map.Entry<String, JsonNode> entry = fields.next();\n"
             + "        String fieldPath = path.isEmpty() ? entry.getKey() : path + \".\" + entry.getKey();\n"
             + "        JsonNode expectedVal = entry.getValue();\n"
             + "        JsonNode actualVal = actual.path(entry.getKey());\n"
             + "        if (expectedVal.isObject()) {\n"
             + "            validateJsonFields(expectedVal, actualVal, fieldPath);\n"
             + "        } else {\n"
             + "            System.out.println(\"[ASSERT] '\" + fieldPath + \"' expected='\" + expectedVal.asText() + \"' actual='\" + actualVal.asText() + \"'\");\n"
             + "            Assert.assertEquals(\"Mismatch at '\" + fieldPath + \"'\", expectedVal.asText(), actualVal.asText());\n"
             + "        }\n"
             + "    }\n"
             + "}\n\n"
             + "=== IMPLEMENTATION RULES ===\n\n"
             + "RULE 1 — Include ALL mandatory methods above verbatim.\n"
             + "RULE 2 — Add any EXTRA steps needed by the feature file beyond those listed above.\n"
             + "RULE 3 — Parameter count must match step annotation captures exactly.\n"
             + "         {string}=String, {int}=int. Count must match Java signature.\n"
             + "RULE 4 — NEVER use RestAssured.lastResponse() — always use the 'response' field.\n"
             + "RULE 5 — NEVER use TestNG. Use only JUnit (org.junit.Assert).\n"
             + "RULE 6 — Add Javadoc to every method explaining: what it does, params, assertions.\n"
             + "RULE 7 — NEVER hardcode token values.\n"
             + "RULE 8 — loadJson() reads from classpath (src/test/resources/testdata/" + featureName + "/).\n\n"
             + "Output the complete Java class now:";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Input context builder
    // ─────────────────────────────────────────────────────────────────────────
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
