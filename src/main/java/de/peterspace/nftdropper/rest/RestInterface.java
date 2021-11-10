package de.peterspace.nftdropper.rest;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.peterspace.nftdropper.component.NftMinter;
import de.peterspace.nftdropper.component.NftSupplier;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class RestInterface {

	private final NftSupplier nftSupplier;
	private final NftMinter nftMinter;

	@GetMapping("address")
	public String getAddress() {
		return nftMinter.getPaymentAddress();
	}

	@GetMapping("tokensLeft")
	public int getTokensLeft() {
		return nftSupplier.tokensLeft();
	}

}
