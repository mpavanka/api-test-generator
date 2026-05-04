package com.sdet.testgen.model;

import lombok.Data;

@Data
public class GitHubPushRequest {
    private String gherkinContent;
    private String stepDefinitionsContent;
    private String apiName;
    private String githubToken;
    private String repoOwner;
    private String repoName;
    private String baseBranch;
    private String featureBranch;
}
