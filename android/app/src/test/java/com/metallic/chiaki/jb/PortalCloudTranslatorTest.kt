package com.metallic.chiaki.jb

import com.metallic.chiaki.jb.translate.PortalCloudTranslator
import org.junit.Assert.*
import org.junit.Test

class PortalCloudTranslatorTest {
    @Test fun strictHttpsEndpointExcludesCredentialsAndQuery() {
        assertTrue(PortalCloudTranslator.validEndpoint("https://example.com/v1/chat/completions"))
        assertTrue(PortalCloudTranslator.validEndpoint("https://api.groq.com/openai/v1/chat/completions"))
        assertTrue(PortalCloudTranslator.validEndpoint("https://api.deepseek.com/chat/completions"))
        assertFalse(PortalCloudTranslator.validEndpoint("http://example.com/v1/chat/completions"))
        assertFalse(PortalCloudTranslator.validEndpoint("https://user:pass@example.com/v1/chat/completions"))
        assertFalse(PortalCloudTranslator.validEndpoint("https://example.com/v1/chat/completions?token=secret"))
        assertFalse(PortalCloudTranslator.validEndpoint("https://example.com/other"))
    }
}
