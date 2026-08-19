package com.agenttrail.capability.ppt.image;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioPptImageStoreTest {

    @Test
    void resolvesStableObjectKeyToShortLivedUrlOnlyAtRenderTime() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.getPresignedObjectUrl(any()))
                .thenReturn("http://127.0.0.1:9000/agenttrail-ppt-images/content-1.png?signature=test");
        MinioPptImageStore store = new MinioPptImageStore(client, "http://127.0.0.1:9000",
                "agenttrail-ppt-images", Duration.ofSeconds(30));

        assertThat(store.resolveForRender("conversation-1/content-1.png"))
                .startsWith("http://127.0.0.1:9000/agenttrail-ppt-images/content-1.png");
        verify(client).getPresignedObjectUrl(any());
    }

    @Test
    void keepsLegacyHttpUrlReadableWithoutSigningAgain() {
        MinioClient client = mock(MinioClient.class);
        MinioPptImageStore store = new MinioPptImageStore(client, "http://127.0.0.1:9000",
                "agenttrail-ppt-images", Duration.ofSeconds(30));

        assertThat(store.resolveForRender("https://images.example.test/already-public.png"))
                .isEqualTo("https://images.example.test/already-public.png");
    }
}
