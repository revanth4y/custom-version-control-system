package com.gitforge.demo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Binds the demo settings, and only under the demo profile.
 *
 * <p>Scoped rather than global so that a production context has no
 * {@link DemoProperties} bean at all - there is nothing for a stray
 * {@code gitforge.demo.reset} to bind to.
 */
@Configuration
@Profile(DemoDataSeeder.PROFILE)
@EnableConfigurationProperties(DemoProperties.class)
public class DemoConfig {
}
