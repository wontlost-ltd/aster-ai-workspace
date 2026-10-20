package com.wontlost.aster.aiw.pro;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wontlost.agui.pro.license.License;
import com.wontlost.agui.pro.license.LicenseKey;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review Focus #5：只有有效且覆盖该功能的 key 才启用 Pro 功能。 */
class ProFeaturesTest {
    private static final LicenseKey KEY = new LicenseKey(
            "Aster", "agui-vaadin-pro", Set.of("conversations"), 5, Instant.now(), LocalDate.now().plusYears(1));

    @Test
    void enabledOnlyWithValidCoveringKey() {
        assertTrue(new ProFeatures(() -> new License.Status(Optional.of(KEY), Optional.empty())).enabled("conversations"));
        assertFalse(new ProFeatures(() -> new License.Status(Optional.of(KEY), Optional.empty())).enabled("audit"));
        assertFalse(new ProFeatures(() -> new License.Status(Optional.empty(), Optional.of("no key"))).enabled("conversations"));
    }

    @Test
    void keyWithProblemIsNotEnabledEvenIfItCoversTheFeature() {
        var expired = new License.Status(Optional.of(KEY), Optional.of("expired"));
        assertFalse(new ProFeatures(() -> expired).enabled("conversations"));
    }

    @Test
    void wildcardKeyCoversEveryFeature() {
        var all = new LicenseKey("Aster", "agui-vaadin-pro", Set.of("*"), 0, Instant.now(), null);
        var features = new ProFeatures(() -> new License.Status(Optional.of(all), Optional.empty()));
        assertTrue(features.enabled("conversations"));
        assertTrue(features.enabled("audit"));
    }

    @Test
    void noConfiguredKeyMeansDisabled(@TempDir Path home) {
        // 隔离开发机上的 ~/.agui-pro/license.key：空目录下真实 License 状态一律关闭
        System.setProperty(License.HOME_PROPERTY, home.toString());
        License.configure(null);
        try {
            assertFalse(ProFeatures.fromLicense().enabled("conversations"));
        } finally {
            System.clearProperty(License.HOME_PROPERTY);
            License.configure(null);
        }
    }
}
