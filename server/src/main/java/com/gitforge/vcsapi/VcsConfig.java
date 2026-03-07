package com.gitforge.vcsapi;

import com.gitforge.vcs.repository.VcsRepositoryFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Exposes the version-control engine to Spring.
 *
 * <p>The factory is plain Java with no framework dependencies; this class exists
 * only to construct it from configuration and hand it to the container. That
 * boundary is deliberate — the engine stays testable without an application
 * context, and nothing in it knows Spring exists.
 */
@Configuration
@EnableConfigurationProperties(VcsStorageProperties.class)
public class VcsConfig {

    @Bean
    public VcsRepositoryFactory vcsRepositoryFactory(VcsStorageProperties properties) {
        return new VcsRepositoryFactory(Path.of(properties.root()));
    }
}
