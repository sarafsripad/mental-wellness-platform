package org.mentalwellness.api;

import org.springframework.boot.SpringApplication;

public class TestWellnessApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(WellnessApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
