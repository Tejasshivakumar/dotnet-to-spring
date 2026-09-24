package catalog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Enables {@code @PreAuthorize} on the migrated controllers.
 *
 * <p>MIGRATION: authentication itself was not translated. Configure a {@code SecurityFilterChain}
 * to match the ASP.NET authentication scheme.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {}
