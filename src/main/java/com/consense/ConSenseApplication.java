package com.consense;

import com.consense.config.ConsenseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(ConsenseProperties.class)
public class ConSenseApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConSenseApplication.class, args);
    }
}
