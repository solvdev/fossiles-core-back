package com.fossiles.fossilescorebackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FossilesCoreBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FossilesCoreBackendApplication.class, args);
    }

}
