package com.seatvault.seat_vault;

import com.seatvault.seat_vault.config.HoldProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(HoldProperties.class)
public class SeatVaultApplication {

	public static void main(String[] args) {
		SpringApplication.run(SeatVaultApplication.class, args);
	}

}
