package com.spaceagent.platform.runtime.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ToolCallTextDetectorTest {
    @Test void detectsUnstructuredProtocolWithoutParsingItsArguments() {
        assertThat(ToolCallTextDetector.containsCall("我继续读取。\n<invoke name=\"file_read\"><parameter name=\"path\">a</parameter></invoke>")).isTrue();
        assertThat(ToolCallTextDetector.containsCall("<INVOKE name='not_allowed'" )).isTrue();
        assertThat(ToolCallTextDetector.containsCall("<function_calls>\n" )).isTrue();
        assertThat(ToolCallTextDetector.containsCall("<tool_call>{\"name\":\"file_read\"}</tool_call>" )).isTrue();
    }

    @Test void preservesCodeExamplesAndOrdinaryAnswers() {
        assertThat(ToolCallTextDetector.containsCall("Example:\n```xml\n<invoke name=\"file_read\">\n</invoke>\n```" )).isFalse();
        assertThat(ToolCallTextDetector.containsCall("Use `<invoke name=\"read\">` in your parser." )).isFalse();
        assertThat(ToolCallTextDetector.containsCall("Example:\n    <invoke name=\"read\">" )).isFalse();
        assertThat(ToolCallTextDetector.containsCall("~~~xml\n<invoke name='read'>\n~~~\nActual answer." )).isFalse();
        assertThat(ToolCallTextDetector.containsCall("Ordinary architecture suggestions" )).isFalse();
        assertThat(ToolCallTextDetector.containsCall(null)).isFalse();
    }

    @Test void stillDetectsInvocationsAfterQuotedExamples() {
        assertThat(ToolCallTextDetector.containsCall("```xml\n<invoke name='example'>\n```\n<invoke name='file_read'>" )).isTrue();
    }
}
