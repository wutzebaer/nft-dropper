package de.peterspace.nftdropper.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import de.peterspace.nftdropper.component.NftMinter;
import de.peterspace.nftdropper.component.NftSupplier;
import de.peterspace.nftdropper.component.ShopItemService;
import de.peterspace.nftdropper.component.ShopItemService.ShopItem;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class IndexController {

	@Value("${use.captcha}")
	private boolean useCaptcha;

	@Value("${hcaptcha.sitekey}")
	private String siteKey;

	@Value("${token.price}")
	private long tokenPrice;

	@Value("${token.maxAmount}")
	private long tokenMaxAmount;

	private final NftSupplier nftSupplier;
	private final NftMinter nftMinter;
	private final ShopItemService shopItemService;

	@GetMapping("/")
	public String index(Model model) {
		model.addAttribute("totalTokens", nftSupplier.getTotalTokens());
		model.addAttribute("tokenLeft", nftSupplier.tokensLeft());
		model.addAttribute("tokenPrice", tokenPrice);
		model.addAttribute("tierPrices", nftMinter.getTierPrices());
		model.addAttribute("tokenMaxAmount", tokenMaxAmount);
		model.addAttribute("paymentAddress", nftMinter.getPaymentAddress());
		model.addAttribute("siteKey", siteKey);
		model.addAttribute("useCaptcha", useCaptcha);
		model.addAttribute("policyId", nftMinter.getPolicy().getPolicyId());
		return "index";
	}

	@GetMapping("/index2.html")
	public String index2(Model model) {
		model.addAttribute("totalTokens", nftSupplier.getTotalTokens());
		model.addAttribute("tokenLeft", nftSupplier.tokensLeft());
		model.addAttribute("tokenPrice", tokenPrice);
		model.addAttribute("tierPrices", nftMinter.getTierPrices());
		model.addAttribute("tokenMaxAmount", tokenMaxAmount);
		model.addAttribute("paymentAddress", nftMinter.getPaymentAddress());
		model.addAttribute("siteKey", siteKey);
		model.addAttribute("useCaptcha", useCaptcha);
		model.addAttribute("shopItems", shopItemService.getShopItems());
		model.addAttribute("policyId", nftMinter.getPolicy().getPolicyId());
		return "index2";
	}

}