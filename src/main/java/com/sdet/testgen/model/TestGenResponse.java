package com.sdet.testgen.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TestGenResponse {
    private String gherkin;
    private String stepDefinitions;
    private int count;
    private String error;

    public static TestGenResponse success(String gherkin, String stepDefs, int count) {
        return new TestGenResponse(gherkin, stepDefs, count, null);
    }

    public static TestGenResponse error(String message) {
        return new TestGenResponse(null, null, 0, message);
    }
}
