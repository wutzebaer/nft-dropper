package de.peterspace.nftdropper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NftDropperApplication {

	public static void main(String[] args) {
		SpringApplication.run(NftDropperApplication.class, args);
	}

}
