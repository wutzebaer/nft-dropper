package de.peterspace.nftdropper.controller;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import de.peterspace.nftdropper.component.CharlySeller;
import de.peterspace.nftdropper.component.CharlyTokenService;
import de.peterspace.nftdropper.component.NftMinter;
import de.peterspace.nftdropper.component.NftSupplier;
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

	@Value("${charly.hunter.min}")
	private int minTokens;

	@Value("${charly.hunter.start}")
	private String hunterStart;

	@Value("${charly.hunter2.start}")
	private String hunter2Start;

	@Value("${charly.hunter2.end}")
	private String hunter2End;

	@Value("${charly.hunter3.start}")
	private String hunter3Start;

	@Value("${charly.hunter3.end}")
	private String hunter3End;

	private final NftSupplier nftSupplier;
	private final CharlySeller charlySeller;
	private final NftMinter nftMinter;
	private final CharlyTokenService charlyTokenService;

	@GetMapping("/")
	public String index(Model model) {
		addAttributes(model);
		if (!StringUtils.isBlank(charlyToken)) {
			return "charly_index";
		}
		return "index";
	}

	@GetMapping("/index2.html")
	public String index2(Model model) {
		addAttributes(model);
		return "index2";
	}

	@GetMapping("/bowl")
	public String bowl(Model model) {
		addAttributes(model);
		return "charly_bowl";
	}

	@GetMapping("/games")
	public String games(Model model) {
		addAttributes(model);
		return "charly_games";
	}

	@GetMapping("/hunter1")
	public String hunter(Model model) {
		addAttributes(model);
		return "charly_hunter1";
	}

	@GetMapping("/hunter2")
	public String hunter2(Model model) {
		addAttributes(model);
		return "charly_hunter2";
	}

	@GetMapping("/hunter3")
	public String hunter3(Model model) {
		addAttributes(model);
		return "charly_hunter3";
	}

	@GetMapping("/goldRush")
	public String goldRush(Model model) {
		addAttributes(model);
		return "charly_gold_rush";
	}

	@GetMapping("/staking")
	public String staking(Model model) {
		addAttributes(model);
		return "charly_staking";
	}

	@GetMapping("/holders")
	public String holders(Model model) {
		addAttributes(model);
		return "charly_holders";
	}

	@GetMapping("/nfts")
	public String nfts(Model model) {
		addAttributes(model);
		return "charly_nfts";
	}

	@GetMapping("/nfts-certificates")
	public String nftsCertificates(Model model) {
		addAttributes(model);
		return "charly_nfts_certificates";
	}

	@GetMapping("/nfts-hood")
	public String nftsHood(Model model) {
		addAttributes(model);
		return "charly_nfts_hood";
	}

	@GetMapping("/nfts-seven")
	public String nftsSeven(Model model) {
		addAttributes(model);
		return "charly_nfts_seven";
	}

	@GetMapping("/nfts-goldRush")
	public String nftsGoldRush(Model model) {
		addAttributes(model);
		return "charly_nfts_goldRush";
	}

	@GetMapping("/team")
	public String team(Model model) {
		addAttributes(model);
		return "charly_team";
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
			model.addAttribute("policyId", nftMinter.getPolicy().getPolicyId());
			model.addAttribute("availableTokens", nftSupplier.getAvailableTokens());
		} else {
			model.addAttribute("totalTokens", 77777777777l);
			model.addAttribute("tokenLeft", charlySeller.tokensLeft());
			model.addAttribute("tokenPrice", tokenPrice);
			model.addAttribute("paymentAddress", charlySeller.getPaymentAddress());
			model.addAttribute("policyId", charlySeller.getCharlyTokenPolicyId());
			model.addAttribute("minTokens", minTokens);
			model.addAttribute("hunterStart", hunterStart);
			model.addAttribute("hunter2Start", hunter2Start);
			model.addAttribute("hunter2End", hunter2End);
			model.addAttribute("hunter3Start", hunter3Start);
			model.addAttribute("hunter3End", hunter3End);
			model.addAttribute("charlyTokenService", charlyTokenService);
		}
	}

}