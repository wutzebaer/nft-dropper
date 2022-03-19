package de.peterspace.nftdropper.controller;

import java.util.ArrayList;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import de.peterspace.nftdropper.component.CharlySeller;
import de.peterspace.nftdropper.component.NftMinter;
import de.peterspace.nftdropper.component.NftSupplier;
import de.peterspace.nftdropper.component.ShopItemService;
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

	@Value("${charly.token}")
	private String charlyToken;

	private final NftSupplier nftSupplier;
	private final CharlySeller charlySeller;
	private final NftMinter nftMinter;
	private final ShopItemService shopItemService;

	@GetMapping("/")
	public String index(Model model) {
		addAttributes(model);
		return "index";
	}

	@GetMapping("/index2.html")
	public String index2(Model model) {
		addAttributes(model);
		return "index2";
	}

	@GetMapping("/index3.html")
	public String index3(Model model) {
		addAttributes(model);
		return "index3";
	}

	@GetMapping("/bowl")
	public String bowl(Model model) {
		addAttributes(model);
		return "bowl";
	}

	private void addAttributes(Model model) {
		if (StringUtils.isBlank(charlyToken)) {
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
			model.addAttribute("availableTokens", nftSupplier.getAvailableTokens());
		} else {
			model.addAttribute("totalTokens", 77777777777l);
			model.addAttribute("tokenLeft", charlySeller.tokensLeft());
			model.addAttribute("tokenPrice", tokenPrice);
			model.addAttribute("paymentAddress", charlySeller.getPaymentAddress());
			model.addAttribute("policyId", charlySeller.getCharlyTokenPolicyId());
		}
	}

}