package com.agenttrail.loop.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolArgumentsTest {

    @Test
    void blankNullAndNonObjectInputsBehaveLikeEmptyArguments() throws Exception {
        for (String input : new String[]{null, "", "  ", "[]", "42", "\"text\""}) {
            ToolArguments arguments = ToolArguments.parse(input);

            assertThat(arguments.text("missing")).isNull();
            assertThat(arguments.integer("missing")).isNull();
            assertThat(arguments.longValue("missing")).isNull();
            assertThat(arguments.flag("missing")).isFalse();
        }
    }

    @Test
    void readsTextWhileToleratingScalarTypeMismatches() throws Exception {
        ToolArguments arguments = ToolArguments.parse("""
                {"text":"hello","number":12,"boolean":true,"nothing":null}
                """);

        assertThat(arguments.text("text")).isEqualTo("hello");
        assertThat(arguments.text("number")).isEqualTo("12");
        assertThat(arguments.text("boolean")).isEqualTo("true");
        assertThat(arguments.text("nothing")).isNull();
    }

    @Test
    void readsIntegersFromNumbersAndTrimmedStringsAndRejectsOtherValues() throws Exception {
        ToolArguments arguments = ToolArguments.parse("""
                {"number":12,"string":" 34 ","decimal":5.9,"bad":"five","nothing":null}
                """);

        assertThat(arguments.integer("number")).isEqualTo(12);
        assertThat(arguments.integer("string")).isEqualTo(34);
        assertThat(arguments.integer("decimal")).isEqualTo(5);
        assertThat(arguments.integer("bad")).isNull();
        assertThat(arguments.integer("nothing")).isNull();
        assertThat(arguments.longValue("number")).isEqualTo(12L);
    }

    @Test
    void booleanFlagsMustBeExplicitlyTrue() throws Exception {
        ToolArguments arguments = ToolArguments.parse("""
                {"nativeTrue":true,"nativeFalse":false,"textTrue":" true ","textFalse":"yes","nothing":null}
                """);

        assertThat(arguments.flag("nativeTrue")).isTrue();
        assertThat(arguments.flag("nativeFalse")).isFalse();
        assertThat(arguments.flag("textTrue")).isTrue();
        assertThat(arguments.flag("textFalse")).isFalse();
        assertThat(arguments.flag("nothing")).isFalse();
    }
}
