package com.seatvault.seat_vault;

import com.seatvault.seat_vault.config.HoldProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// Scheduling (@EnableScheduling) lives on com.seatvault.seat_vault.config.SchedulingConfig,
// gated by seatvault.sweep.scheduling.enabled - see that class's Javadoc for why it is not
// here unconditionally.
@SpringBootApplication
@EnableConfigurationProperties(HoldProperties.class)
public class SeatVaultApplication {

	public static void main(String[] args) {
		SpringApplication.run(SeatVaultApplication.class, args);
	}

}
