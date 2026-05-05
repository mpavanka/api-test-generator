package com.sdet.testgen.model;

import lombok.Data;
import java.util.Map;

@Data
public class GitHubPushRequest {
    private String gherkinContent;
    private String stepDefinitionsContent;
    private Map<String, String> testDataFiles; // key=TC_01, value=JSON content
    private String apiName;
    private String githubToken;
    private String repoOwner;
    private String repoName;
    private String baseBranch;
    private String featureBranch;
}
