package com.sdet.testgen.controller;

import com.sdet.testgen.model.GitHubPushRequest;
import com.sdet.testgen.model.GitHubPushResponse;
import com.sdet.testgen.model.TestGenRequest;
import com.sdet.testgen.model.TestGenResponse;
import com.sdet.testgen.service.GitHubService;
import com.sdet.testgen.service.OllamaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TestGenController {

    private final OllamaService ollamaService;
    private final GitHubService gitHubService;

    @GetMapping("/health")
    public ResponseEntity<String> health() { return ResponseEntity.ok("API Test Generator running"); }

    @PostMapping("/generate-tests")
    public ResponseEntity<TestGenResponse> generate(@RequestBody TestGenRequest req) {
        log.info("Generate: mode={}, feature={}", req.getInputMode(), req.getFeatureName());
        return ResponseEntity.ok(ollamaService.generate(req));
    }

    @PostMapping("/push-to-github")
    public ResponseEntity<GitHubPushResponse> push(@RequestBody GitHubPushRequest req) {
        log.info("Push: repo={}/{} branch={}", req.getRepoOwner(), req.getRepoName(), req.getFeatureBranch());
        return ResponseEntity.ok(gitHubService.push(req));
    }
}
