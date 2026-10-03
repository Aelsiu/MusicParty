package org.thornex.musicparty.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.thornex.musicparty.controller.AuthController;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppBrandingDefaultsTest {
    private static final Map<String,String> DEFAULT_BRANDING = Map.of(
            "authorName", "ThorNex X Aelsiu", "backWords", "MUSIC PARTY");

    private StandardEnvironment packagedEnvironment() throws IOException {
        var environment = new StandardEnvironment();
        // The build host's deployment settings must not change the default case.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (var source : new YamlPropertySourceLoader().load("packagedApplication", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    private Map<String,String> exposedBranding(StandardEnvironment environment) {
        AppProperties defaults = Binder.get(environment).bind("app.music-api", Bindable.of(AppProperties.class)).get();
        return new AuthController(null, defaults).getConfig();
    }

    @Test void packagedYamlAndJavaFallbackExposeTheSameDefaultBranding() throws IOException {
        assertEquals(DEFAULT_BRANDING, exposedBranding(packagedEnvironment()));
        assertEquals(DEFAULT_BRANDING, new AuthController(null, new AppProperties()).getConfig());
    }

    @Test void runtimeEnvironmentPreservesCustomBranding() throws IOException {
        var environment = packagedEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("deploymentEnvironment", Map.of(
                "APP_AUTHOR_NAME", "Custom Docker Author", "APP_BACK_WORDS", "Custom Docker Background")));
        assertEquals(Map.of("authorName", "Custom Docker Author", "backWords", "Custom Docker Background"), exposedBranding(environment));
    }

    @Test void externalDeploymentPropertiesOverridePackagedBrandingPlaceholders() throws IOException {
        var environment = packagedEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("mountedApplicationProperties", Map.of(
                "app.music-api.author-name", "Configured Author", "app.music-api.back-words", "Configured Background")));
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("deploymentEnvironment", Map.of(
                "APP_AUTHOR_NAME", "Environment Fallback", "APP_BACK_WORDS", "Environment Fallback")));
        assertEquals(Map.of("authorName", "Configured Author", "backWords", "Configured Background"), exposedBranding(environment));
    }
}
