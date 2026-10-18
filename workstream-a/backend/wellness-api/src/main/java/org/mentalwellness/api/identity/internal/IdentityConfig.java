package org.mentalwellness.api.identity.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Web application only: batch runs such as the research export never connect to identity_db. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableConfigurationProperties(IdentityProperties.class)
class IdentityConfig {

	@Bean
	IdentityStore identityStore(IdentityProperties properties) {
		return new IdentityStore(properties);
	}

}
