package org.mentalwellness.api.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables scheduled housekeeping (e.g. retrying deferred audio deletions). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {

}
