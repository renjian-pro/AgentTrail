package com.agenttrail.evaluation;

import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Owns the merge between the read-only YAML baseline and the admin-writable {@code golden_case} table —
 * both the case-management API and {@link GoldenEvaluationService} go through here rather than picking
 * one source themselves, so "what actually runs" and "what the admin page shows" can never drift apart.
 */
public class GoldenCaseService {
    private final GoldenCaseRepository repository;

    public GoldenCaseService(GoldenCaseRepository repository) {
        this.repository = repository;
    }

    public List<GoldenCaseView> listAll() {
        return Stream.concat(
                    GoldenTaskRunner.loadAll().stream().map(GoldenCaseView::ofBuiltin),
                    repository.findAll().stream().map(GoldenCaseView::ofRecord))
                .sorted(Comparator.comparing(GoldenCaseView::id))
                .toList();
    }

    /** What {@link GoldenEvaluationService} actually runs: YAML baseline + every DB-managed case. */
    public List<GoldenCase> casesForExecution() {
        List<GoldenCase> extra = repository.findAll().stream().map(GoldenCaseRecord::toGoldenCase).toList();
        return GoldenTaskRunner.loadAll(extra);
    }

    public GoldenCaseView create(GoldenCaseRequest request) {
        String id = (request.id() == null || request.id().isBlank())
                ? "promoted-" + UUID.randomUUID().toString().substring(0, 8) : request.id().trim();
        requireNonBlank(request.dimension(), "dimension");
        requireNonBlank(request.question(), "question");
        Set<String> builtinIds = builtinIds();
        if (builtinIds.contains(id)) {
            throw new ResponseStatusException(CONFLICT, "id 与内建用例冲突，内建用例只读: " + id);
        }
        if (repository.findById(id).isPresent()) {
            throw new ResponseStatusException(CONFLICT, "用例 id 已存在: " + id);
        }
        String source = request.source() == null || request.source().isBlank()
                ? GoldenCaseRecord.SOURCE_MANUAL : request.source();
        long now = System.currentTimeMillis();
        GoldenCaseRecord record = new GoldenCaseRecord(id, request.dimension(), request.question(),
                request.asUser() == null ? "" : request.asUser(), request.referenceSql(),
                normalizeAssertions(request.assertions()), normalizeToolCalls(request.expectedToolCalls()),
                source, request.sourceConversationId(), now, now);
        repository.insert(record);
        return GoldenCaseView.ofRecord(record);
    }

    public GoldenCaseView update(String id, GoldenCaseRequest request) {
        GoldenCaseRecord existing = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        builtinIds().contains(id) ? "内建用例只读，不能编辑: " + id : "用例不存在: " + id));
        requireNonBlank(request.dimension(), "dimension");
        requireNonBlank(request.question(), "question");
        GoldenCaseRecord updated = new GoldenCaseRecord(id, request.dimension(), request.question(),
                request.asUser() == null ? "" : request.asUser(), request.referenceSql(),
                normalizeAssertions(request.assertions()), normalizeToolCalls(request.expectedToolCalls()),
                existing.source(), existing.sourceConversationId(), existing.createdAtMillis(),
                System.currentTimeMillis());
        repository.update(updated);
        return GoldenCaseView.ofRecord(updated);
    }

    public void delete(String id) {
        if (!repository.deleteById(id)) {
            throw new ResponseStatusException(NOT_FOUND,
                    builtinIds().contains(id) ? "内建用例只读，不能删除: " + id : "用例不存在: " + id);
        }
    }

    private Set<String> builtinIds() {
        return GoldenTaskRunner.loadAll().stream().map(GoldenCase::id).collect(Collectors.toSet());
    }

    private static List<Map<String, Object>> normalizeAssertions(List<Map<String, Object>> assertions) {
        if (assertions == null || assertions.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "至少需要一条断言，否则这条用例永远算通过");
        }
        return assertions;
    }

    private static List<String> normalizeToolCalls(List<String> expectedToolCalls) {
        return expectedToolCalls == null ? List.of() : expectedToolCalls;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, field + " 不能为空");
        }
    }
}
