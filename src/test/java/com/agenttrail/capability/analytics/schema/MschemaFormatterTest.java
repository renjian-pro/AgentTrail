package com.agenttrail.capability.analytics.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MschemaFormatterTest {
    private final Mschema schema = new Mschema("agenttrail", List.of(
            new Mschema.TableDef("rental", "租赁记录", List.of(
                    new Mschema.FieldDef("rental_id", "BIGINT", "主键", true, List.of()),
                    new Mschema.FieldDef("status", "VARCHAR", "状态", false, List.of("RETURNED", "RENTED"))),
                    List.of(new Mschema.ForeignKeyDef("customer_id", "customer", "customer_id"))),
            new Mschema.TableDef("customer", "客户", List.of(), List.of())));

    @Test
    void formatsListsAndDetailsWithoutLeakingFieldsIntoTheList() {
        String list = MschemaFormatter.formatTableList(schema);
        assertThat(list).contains("## rental", "关联: customer").doesNotContain("rental_id");

        String details = MschemaFormatter.formatTables(schema, List.of("rental"));
        assertThat(details).contains("(rental_id: BIGINT, 主键, 主键)")
                .contains("Examples: [RETURNED, RENTED]")
                .contains("rental.customer_id -> customer.customer_id");
    }

    @Test
    void roundTripsThroughJacksonForRedisCaching() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readValue(mapper.writeValueAsString(schema), Mschema.class)).isEqualTo(schema);
    }

    @Test
    void givesAnExplicitErrorForUnknownDescribeTables() {
        MschemaIntrospector introspector = Mockito.mock(MschemaIntrospector.class);
        Mockito.when(introspector.introspect()).thenReturn(schema);
        MschemaSchemaProvider provider = new MschemaSchemaProvider(new MschemaCacheService(
                introspector, new ObjectMapper(), new com.agenttrail.capability.analytics.config.AnalyticsSchemaProperties(),
                null, null));

        assertThat(provider.describeTables(List.of("not_a_table"))).contains("未找到分析表");
    }
}
