package com.sdet.testgen.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class TestGenRequest {
    private String inputMode;        // endpoint | swagger | rawjson | requirements
    private String httpMethod;
    private String endpointUrl;
    private String apiDescription;   // also used as full requirements context
    private String swaggerJson;
    private String requestJson;
    private String responseJson;
    private String coverageFocus;
    private int testCaseCount;
    private String featureName;
    private List<EndpointDef> endpoints;

    @Data
    public static class EndpointDef {
        private int id;
        private String tcNum;
        private String method;
        private String url;
        private String baseUrl;
        private String description;
        private String requestBody;
        private Map<String, String> params;
        private Map<String, String> headers;
        private List<Map<String, String>> responses;
        private boolean validateResp;
        private String responseBody;
    }
}
