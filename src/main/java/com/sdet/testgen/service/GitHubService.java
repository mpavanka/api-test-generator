package com.sdet.testgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sdet.testgen.model.GitHubPushRequest;
import com.sdet.testgen.model.GitHubPushResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubService {

    private final ObjectMapper objectMapper;
    private static final String GITHUB_API     = "https://api.github.com";
    private static final String FEATURE_PATH   = "src/test/resources/features/";
    private static final String STEP_DEFS_PATH = "src/test/java/stepDefinitions/";

    public GitHubPushResponse push(GitHubPushRequest req) {
        try {
            WebClient client = buildClient(req.getGithubToken());
            String owner     = req.getRepoOwner();
            String repo      = req.getRepoName();
            String base      = req.getBaseBranch();
            String newBranch = req.getFeatureBranch();
            String apiName   = sanitizeName(req.getApiName());

            String sha = getBranchSha(client, owner, repo, base);
            createBranch(client, owner, repo, newBranch, sha);

            Map<String, String> files = new LinkedHashMap<>();
            files.put(FEATURE_PATH   + apiName + ".feature",             req.getGherkinContent());
            files.put(STEP_DEFS_PATH + apiName + "StepDefinitions.java", req.getStepDefinitionsContent());

            for (Map.Entry<String, String> e : files.entrySet()) {
                log.info("Pushing: {}", e.getKey());
                pushFile(client, owner, repo, newBranch, e.getKey(), e.getValue(),
                        "feat: add " + apiName + " BDD tests — " + e.getKey());
            }
            return GitHubPushResponse.success(newBranch, owner, repo);
        } catch (WebClientResponseException e) {
            log.error("GitHub API error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return GitHubPushResponse.error("GitHub API error " + e.getStatusCode()
                    + ": " + extractMsg(e.getResponseBodyAsString()));
        } catch (Exception e) {
            log.error("Push failed: {}", e.getMessage(), e);
            return GitHubPushResponse.error("Push failed: " + e.getMessage());
        }
    }

    private WebClient buildClient(String token) {
        return WebClient.builder().baseUrl(GITHUB_API)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
    }

    private String getBranchSha(WebClient c, String o, String r, String b) throws Exception {
        String resp = c.get().uri("/repos/{o}/{r}/git/ref/heads/{b}", o, r, b)
                .retrieve().bodyToMono(String.class).block();
        return objectMapper.readTree(resp).path("object").path("sha").asText();
    }

    private void createBranch(WebClient c, String o, String r, String branch, String sha) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("ref", "refs/heads/" + branch); body.put("sha", sha);
            c.post().uri("/repos/{o}/{r}/git/refs", o, r).bodyValue(body).retrieve().bodyToMono(String.class).block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() != 422) throw e;
            log.warn("Branch '{}' already exists, updating files.", branch);
        }
    }

    private void pushFile(WebClient c, String o, String r, String branch, String path, String content, String msg) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("message", msg); body.put("content", encoded); body.put("branch", branch);
        try {
            String existing = c.get().uri("/repos/{o}/{r}/contents/{p}?ref={b}", o, r, path, branch)
                    .retrieve().bodyToMono(String.class).block();
            String sha = objectMapper.readTree(existing).path("sha").asText();
            if (!sha.isEmpty()) body.put("sha", sha);
        } catch (WebClientResponseException e) { if (e.getStatusCode().value() != 404) throw e; }
        c.put().uri("/repos/{o}/{r}/contents/{p}", o, r, path).bodyValue(body).retrieve().bodyToMono(String.class).block();
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "Generated";
        String[] words = name.trim().replaceAll("[^a-zA-Z0-9 ]", "").split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        return sb.isEmpty() ? "Generated" : sb.toString();
    }

    private String extractMsg(String body) {
        try { return objectMapper.readTree(body).path("message").asText(body); } catch (Exception e) { return body; }
    }
}
