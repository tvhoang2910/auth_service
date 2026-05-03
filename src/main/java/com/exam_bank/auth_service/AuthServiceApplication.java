package com.exam_bank.auth_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.cdimascio.dotenv.Dotenv;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AuthServiceApplication {

    public static void main(String[] args) {

        // 🔥 Load .env từ root project
        Dotenv dotenv = Dotenv.configure()
                .directory("../") // vì .env nằm ngoài auth_service
                .ignoreIfMalformed()
                .ignoreIfMissing()
                .load();

        // 🔥 Đẩy vào System properties để Spring đọc
        dotenv.entries().forEach(entry ->
                System.setProperty(entry.getKey(), entry.getValue())
        );

        SpringApplication.run(AuthServiceApplication.class, args);
    }
}