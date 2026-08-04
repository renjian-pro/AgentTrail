package com.agenttrail.capability.analytics.glossary;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class GlossaryCatalogTest {
    @Test
    void loadsTermsAndMatchesSynonymsPrecisely() {
        GlossaryCatalog catalog = new GlossaryCatalog(new DefaultResourceLoader()
                .getResource("classpath:analytics/glossary.yml"));
        catalog.load();
        assertThat(catalog.all()).hasSizeGreaterThanOrEqualTo(7);
        assertThat(catalog.lookup("活跃用户")).isNotNull();
        assertThat(catalog.lookup("活跃")).isNull();
        assertThat(catalog.format("时间范围")).contains("时间口径");
    }
}
