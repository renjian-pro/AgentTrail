package com.agenttrail.web;

import com.agenttrail.evaluation.GoldenEvaluationHistoryItem;
import com.agenttrail.evaluation.GoldenEvaluationService;
import com.agenttrail.evaluation.GoldenEvaluationTaskResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Admin-facing async evaluation endpoints. Authorization follows the existing admin route policy. */
@RestController
public class GoldenEvaluationController {
    private final GoldenEvaluationService evaluationService;

    public GoldenEvaluationController(GoldenEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping("/agent/v1/evaluation/run")
    public GoldenEvaluationTaskResponse run() {
        return evaluationService.start();
    }

    @GetMapping("/agent/v1/evaluation/{taskId}")
    public GoldenEvaluationTaskResponse status(@PathVariable String taskId) {
        return evaluationService.status(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Evaluation task not found: " + taskId));
    }

    @GetMapping("/agent/v1/evaluation/history")
    public List<GoldenEvaluationHistoryItem> history() {
        return evaluationService.history();
    }
}
