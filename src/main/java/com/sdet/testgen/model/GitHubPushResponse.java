package com.sdet.testgen.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GitHubPushResponse {
    private boolean success;
    private String message;
    private String branchUrl;
    private String prUrl;

    public static GitHubPushResponse success(String branch, String owner, String repo) {
        String branchUrl = "https://github.com/" + owner + "/" + repo + "/tree/" + branch;
        String prUrl     = "https://github.com/" + owner + "/" + repo + "/compare/test..." + branch;
        return new GitHubPushResponse(true,
                "3 files pushed to branch: " + branch, branchUrl, prUrl);
    }

    public static GitHubPushResponse error(String message) {
        return new GitHubPushResponse(false, message, null, null);
    }
}
