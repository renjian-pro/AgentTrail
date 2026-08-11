package com.agenttrail.capability.file;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileUploadPolicyTest {
    @Test
    void acceptsAllowedTypeWithinSizeLimit() {
        assertThatCode(() -> new FileUploadPolicy(10, Set.of("text/plain"))
                .validate("notes.txt", "TEXT/PLAIN", 10)).doesNotThrowAnyException();
    }

    @Test
    void rejectsOversizedAndUnknownUploadsBeforePersistence() {
        FileUploadPolicy policy = new FileUploadPolicy(10, Set.of("text/plain"));

        assertThatThrownBy(() -> policy.validate("notes.txt", "text/plain", 11))
                .isInstanceOf(FileUploadRejectedException.class);
        assertThatThrownBy(() -> policy.validate("notes.txt", "application/x-executable", 1))
                .isInstanceOf(FileUploadRejectedException.class);
    }
}
