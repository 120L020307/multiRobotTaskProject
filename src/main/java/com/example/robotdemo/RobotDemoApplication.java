package com.example.robotdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RobotDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(RobotDemoApplication.class, args);
    }
}
