package com.seatvault.seat_vault;

import com.seatvault.seat_vault.config.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class SeatVaultApplicationTests {

	@Test
	void contextLoads() {
	}

}
