package com.travel.travel_recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseProfileConfigurationTests {

    @Test
    void databaseSafetySettingsApplyToEveryProfile() {
        for (String profile : new String[]{"default", "sqlserver", "sqlserver-windows"}) {
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> {
                        var environment = context.getEnvironment();
                        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                                .as("DDL setting for %s", profile).isEqualTo("none");
                        assertThat(environment.getProperty("spring.sql.init.mode"))
                                .as("SQL initialization for %s", profile).isEqualTo("never");
                        assertThat(environment.getProperty("spring.jpa.open-in-view"))
                                .as("Open-in-view for %s", profile).isEqualTo("false");
                        assertThat(environment.getProperty("spring.autoconfigure.exclude") != null)
                                .as("Database exclusions for %s", profile)
                                .isEqualTo(profile.equals("default"));
                    });
        }
    }
}
