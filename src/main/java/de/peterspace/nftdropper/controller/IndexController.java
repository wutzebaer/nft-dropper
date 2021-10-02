package de.peterspace.nftdropper.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import de.peterspace.nftdropper.component.NftSupplier;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class IndexController {

	@Value("${token.price}")
	private long tokenPrice;

	private final NftSupplier nftSupplier;

	@GetMapping("/")
	public String index(Model model) {
		model.addAttribute("tokenLeft", nftSupplier.tokensLeft());
		model.addAttribute("tokenPrice", tokenPrice);
		return "index";
	}

}