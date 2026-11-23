package me.rerere.rikkahub.data.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ProactiveErrorCategoryTest {
    @Test fun arbitraryUpstreamErrorCannotBeEchoedIntoTheTrace() {
        assertEquals("other", ProactiveMessageTrace.errorCategory(
            RuntimeException("private chat, arbitrary secret and tool arguments")
        ))
    }

    @Test fun emptyStreamIsStillDiagnosableWithoutItsRawResponse() {
        assertEquals("empty_stream", ProactiveMessageTrace.errorCategory(
            RuntimeException("empty_stream: private upstream response")
        ))
    }
    @Test fun providerAndNetworkFailuresHaveSafeCategories() {
        assertEquals("provider_error", ProactiveMessageTrace.errorCategory(
            me.rerere.ai.util.HttpException("private upstream body")
        ))
        assertEquals("network", ProactiveMessageTrace.errorCategory(java.io.IOException("private URL")))
        assertEquals("timeout", ProactiveMessageTrace.errorCategory(java.net.SocketTimeoutException()))
    }
}
