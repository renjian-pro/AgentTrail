package com.agenttrail.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只跑纯逻辑（合并/校验），不碰真实 MySQL——数据库读写的正确性交给 {@code GoldenCaseRepositoryIT}。
 */
class GoldenCaseServiceTest {

    private static final List<Map<String, Object>> SOME_ASSERTION =
            List.of(Map.of("type", "tool_called", "name", "execute_sql"));

    @Test
    void rejectsANewCaseWhoseIdCollidesWithABuiltinYamlCase() {
        GoldenCaseRepository repository = mock(GoldenCaseRepository.class);
        GoldenCaseService service = new GoldenCaseService(repository);
        GoldenCaseRequest request = new GoldenCaseRequest("sql-001", "sql_correctness", "count rentals",
                "admin", null, SOME_ASSERTION, null, null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("sql-001");
        verify(repository, never()).insert(any());
    }

    @Test
    void rejectsANewCaseWhoseIdAlreadyExistsInTheTable() {
        GoldenCaseRepository repository = mock(GoldenCaseRepository.class);
        when(repository.findById("dup-1")).thenReturn(Optional.of(sampleRecord("dup-1")));
        GoldenCaseService service = new GoldenCaseService(repository);
        GoldenCaseRequest request = new GoldenCaseRequest("dup-1", "sql_correctness", "count rentals",
                "admin", null, SOME_ASSERTION, null, null, null);

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).insert(any());
    }

    @Test
    void rejectsACaseWithNoAssertionsBecauseItWouldAlwaysPass() {
        GoldenCaseRepository repository = mock(GoldenCaseRepository.class);
        GoldenCaseService service = new GoldenCaseService(repository);
        GoldenCaseRequest request = new GoldenCaseRequest(null, "sql_correctness", "count rentals",
                "admin", null, List.of(), null, null, null);

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).insert(any());
    }

    @Test
    void generatesAnIdAndDefaultsSourceToManualWhenTheRequestLeavesBothBlank() {
        GoldenCaseRepository repository = mock(GoldenCaseRepository.class);
        GoldenCaseService service = new GoldenCaseService(repository);
        GoldenCaseRequest request = new GoldenCaseRequest(null, "sql_correctness", "count rentals",
                "admin", null, SOME_ASSERTION, null, null, null);

        GoldenCaseView created = service.create(request);

        assertThat(created.id()).startsWith("promoted-");
        assertThat(created.source()).isEqualTo(GoldenCaseRecord.SOURCE_MANUAL);
    }

    @Test
    void keepsThePromotedSourceAndConversationIdWhenTheCandidateFlowSuppliesThem() {
        GoldenCaseRepository repository = mock(GoldenCaseRepository.class);
        GoldenCaseService service = new GoldenCaseService(repository);
        GoldenCaseRequest request = new GoldenCaseRequest("promoted-abc", "sql_correctness", "count rentals",
                "admin", null, SOME_ASSERTION, null, GoldenCaseRecord.SOURCE_PROMOTED, "conv-42");

        GoldenCaseView created = service.create(request);

        assertThat(created.source()).isEqualTo(GoldenCaseRecord.SOURCE_PROMOTED);
        assertThat(created.sourceConversationId()).isEqualTo("conv-42");
    }

    @Test
    void updateRejectsAnIdThatIsNotInTheTableEvenWhenItMatchesABuiltinCase() {
        GoldenCaseRepository repository = mock(GoldenCaseRepository.class);
        when(repository.findById("sql-001")).thenReturn(Optional.empty());
        GoldenCaseService service = new GoldenCaseService(repository);
        GoldenCaseRequest request = new GoldenCaseRequest("sql-001", "sql_correctness", "count rentals",
                "admin", null, SOME_ASSERTION, null, null, null);

        assertThatThrownBy(() -> service.update("sql-001", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("只读");
    }

    @Test
    void deleteRejectsAnUnknownId() {
        GoldenCaseRepository repository = mock(GoldenCaseRepository.class);
        when(repository.deleteById("missing")).thenReturn(false);
        GoldenCaseService service = new GoldenCaseService(repository);

        assertThatThrownBy(() -> service.delete("missing")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void listAllMarksBuiltinCasesReadOnlyAndDbCasesEditable() {
        GoldenCaseRepository repository = mock(GoldenCaseRepository.class);
        when(repository.findAll()).thenReturn(List.of(sampleRecord("manual-1")));
        GoldenCaseService service = new GoldenCaseService(repository);

        List<GoldenCaseView> views = service.listAll();

        assertThat(views).anySatisfy(view -> {
            assertThat(view.id()).isEqualTo("manual-1");
            assertThat(view.editable()).isTrue();
        });
        assertThat(views).anySatisfy(view -> {
            assertThat(view.source()).isEqualTo(GoldenCaseView.SOURCE_BUILTIN);
            assertThat(view.editable()).isFalse();
        });
    }

    @Test
    void executionListMergesTheYamlBaselineWithEveryDbManagedCase() {
        GoldenCaseRepository repository = mock(GoldenCaseRepository.class);
        when(repository.findAll()).thenReturn(List.of(sampleRecord("manual-1")));
        GoldenCaseService service = new GoldenCaseService(repository);

        List<GoldenCase> cases = service.casesForExecution();

        assertThat(cases).extracting(GoldenCase::id).contains("manual-1", "sql-001");
    }

    private static GoldenCaseRecord sampleRecord(String id) {
        return new GoldenCaseRecord(id, "sql_correctness", "count rentals", "admin", null,
                SOME_ASSERTION, List.of(), GoldenCaseRecord.SOURCE_MANUAL, null, 1L, 1L);
    }
}
