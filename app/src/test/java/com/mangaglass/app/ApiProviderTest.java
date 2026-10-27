package com.mangaglass.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ApiProviderTest {
    @Test public void blankAddressesRouteChatAndModelRequestsToSelectedProvider() {
        String[] bases = {"https://api.openai.com/v1", "https://api.deepseek.com/v1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                "https://dashscope-us.aliyuncs.com/compatible-mode/v1", "https://openrouter.ai/api/v1"};
        for (int i = 0; i < bases.length; i++) {
            Settings.Profile profile = new Settings.Profile(ApiProvider.IDS[i], "  ", "", "test-key", "auto", false, java.util.Collections.emptyList());
            assertEquals("", profile.baseUrl);
            assertEquals(bases[i] + "/chat/completions", TranslationProtocol.endpoint(profile.url));
            assertEquals(bases[i] + "/models", ModelCatalog.endpoint(profile.url));
            assertFalse(profile.ready()); // Model discovery must remain possible before selecting a model.
        }
    }
    @Test public void explicitGatewayOverridesProviderDefault() {
        String url = "https://gateway.example/custom/v1/chat/completions";
        assertEquals(url, ApiProvider.resolve("openai", " " + url + " "));
        assertEquals("https://gateway.example/custom/v1/models", ModelCatalog.endpoint(ApiProvider.resolve("qwen-cn", url)));
    }
    @Test public void legacyAddressesArePreservedAndRecognizedWithoutChangingTheirRoute() {
        for (String id : ApiProvider.IDS) {
            if ("custom".equals(id)) continue;
            String base = ApiProvider.defaultUrl(id);
            assertEquals(id, ApiProvider.infer(base + "/"));
            assertEquals(id, ApiProvider.infer(base + "/chat/completions"));
            assertEquals(base, new Settings.Profile(base, "vision", "test-key").url);
        }
        String gateway = "https://api.openai.com/proxy/v1";
        assertEquals("custom", ApiProvider.infer(gateway));
        assertEquals(gateway, new Settings.Profile(gateway, "vision", "test-key").url);
        assertEquals("deepseek", ApiProvider.infer("https://api.deepseek.com"));
    }
    @Test public void customOrUnknownProvidersRequireAnAddress() {
        assertThrows(IllegalArgumentException.class, () -> ApiProvider.resolve("custom", ""));
        assertThrows(IllegalArgumentException.class, () -> ApiProvider.resolve("unknown", ""));
    }
    @Test public void explicitInvalidAddressCannotFallBackToAnOfficialHost() {
        for (String invalid : new String[]{"http://gateway.example/v1", "https://user@gateway.example/v1",
                "https://gateway.example/v1?key=secret", "https://gateway.example/v1#fragment"}) {
            assertThrows(IllegalArgumentException.class, () -> ApiProvider.resolve("openai", invalid));
        }
    }
}
