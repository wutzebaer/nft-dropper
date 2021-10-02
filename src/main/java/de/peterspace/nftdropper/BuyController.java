package de.peterspace.nftdropper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class BuyController {

	@Value("${token.price}")
	private long tokenPrice;

	@Value("${token.amount}")
	private long tokenAmount;

	private final NftSupplier nftSupplier;

	@GetMapping("/buy")
	public String buy(Model model) {
		model.addAttribute("tokenLeft", nftSupplier.tokensLeft());
		model.addAttribute("tokenPrice", tokenPrice);
		model.addAttribute("tokenAmount", tokenAmount);
		return "buy";
	}

}