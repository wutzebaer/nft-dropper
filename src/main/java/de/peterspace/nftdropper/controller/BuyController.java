package de.peterspace.nftdropper.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import de.peterspace.nftdropper.component.NftMinter;
import de.peterspace.nftdropper.component.NftSupplier;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class BuyController {

	@Value("${token.price}")
	private long tokenPrice;

	@Value("${token.maxAmount}")
	private long tokenMaxAmount;

	private final NftSupplier nftSupplier;
	private final NftMinter nftMinter;

	@GetMapping("/buy")
	public String buy(Model model) {
		model.addAttribute("tokenLeft", nftSupplier.tokensLeft());
		model.addAttribute("tokenPrice", tokenPrice);
		model.addAttribute("tokenMaxAmount", tokenMaxAmount);
		model.addAttribute("paymentAddress", nftMinter.getPaymentAddress());
		return "buy";
	}

}