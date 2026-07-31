package com.agenttrail.loop.tools.websearch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyWebSearchResultParserTest {

    private final TavilyWebSearchResultParser parser = new TavilyWebSearchResultParser();

    @Test
    void parsesResultsWhenTheInnerTextIsANestedJsonString() {
        String raw = """
                [{"type":"text","text":"{\\"results\\":[{\\"url\\":\\"https://a.com\\",\\"title\\":\\"A\\",\\"content\\":\\"content A\\"},{\\"url\\":\\"https://b.com\\",\\"title\\":\\"B\\",\\"content\\":\\"content B\\"}]}"}]
                """;

        List<SearchResult> results = parser.parse(raw);

        assertThat(results).containsExactly(
                new SearchResult("https://a.com", "A", "content A"),
                new SearchResult("https://b.com", "B", "content B"));
    }

    @Test
    void parsesResultsWhenTheInnerTextIsAlreadyAJsonObject() {
        String raw = """
                [{"type":"text","text":{"results":[{"url":"https://a.com","title":"A","content":"content A"}]}}]
                """;

        List<SearchResult> results = parser.parse(raw);

        assertThat(results).containsExactly(new SearchResult("https://a.com", "A", "content A"));
    }

    @Test
    void skipsResultsMissingAUrl() {
        String raw = """
                [{"type":"text","text":"{\\"results\\":[{\\"title\\":\\"no url\\"},{\\"url\\":\\"https://b.com\\",\\"title\\":\\"B\\",\\"content\\":\\"content B\\"}]}"}]
                """;

        List<SearchResult> results = parser.parse(raw);

        assertThat(results).containsExactly(new SearchResult("https://b.com", "B", "content B"));
    }

    @Test
    void returnsAnEmptyListForMalformedJsonInsteadOfThrowing() {
        assertThat(parser.parse("not json at all")).isEmpty();
    }

    @Test
    void returnsAnEmptyListForAnEmptyArray() {
        assertThat(parser.parse("[]")).isEmpty();
    }

    @Test
    void returnsAnEmptyListWhenThereIsNoResultsField() {
        String raw = """
                [{"type":"text","text":"{\\"other\\":[]}"}]
                """;

        assertThat(parser.parse(raw)).isEmpty();
    }
}
