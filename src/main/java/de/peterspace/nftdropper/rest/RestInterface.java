package de.peterspace.nftdropper.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.peterspace.nftdropper.cardano.CardanoCli;
import de.peterspace.nftdropper.cardano.CardanoDbSyncClient;
import de.peterspace.nftdropper.cardano.CardanoDbSyncClient.MintedToken;
import de.peterspace.nftdropper.component.CharlyHunterService;
import de.peterspace.nftdropper.component.NftMinter;
import de.peterspace.nftdropper.component.NftSupplier;
import de.peterspace.nftdropper.component.ShopItemService;
import de.peterspace.nftdropper.component.ShopItemService.ShopItem;
import de.peterspace.nftdropper.model.Address;
import de.peterspace.nftdropper.model.HunterSnapshotRow;
import de.peterspace.nftdropper.repository.AddressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Slf4j
@Validated
public class RestInterface {

	@Value("${hcaptcha.secret}")
	private String hcaptchaSecret;

	@Value("${token.maxAmount}")
	private int tokenMaxAmount;

	private final CardanoCli cardanoCli;
	private final NftSupplier nftSupplier;
	private final NftMinter nftMinter;
	private final AddressRepository addressRepository;
	private final ShopItemService shopItemService;
	private final CardanoDbSyncClient cardanoDbSyncClient;
	private final CharlyHunterService charlyHunterService;

	@GetMapping("address")
	public String getAddress() {
		return nftMinter.getPaymentAddress();
	}

	@GetMapping("tokensLeft")
	public int getTokensLeft() {
		return nftSupplier.tokensLeft();
	}

	@GetMapping("addressTokensLeft")
	public ResponseEntity<Integer> getAddressTokensLeft(String address) {
		Optional<Address> foundAddress = addressRepository.findById(address);
		if (foundAddress.isPresent()) {
			return new ResponseEntity<Integer>(tokenMaxAmount - foundAddress.get().getTokensMinted(), HttpStatus.OK);
		} else {
			return new ResponseEntity<Integer>(HttpStatus.NOT_FOUND);
		}
	}

	@lombok.Value
	public static class CaptchaSolution {
		@NotBlank
		String token;
	}

	@lombok.Value
	public static class AddressResult {
		@NotBlank
		String address;
	}

	@PostMapping("captchaAddress")
	public ResponseEntity<AddressResult> getAddress(@Valid CaptchaSolution captchaSolution) throws Exception {

		try (CloseableHttpClient client = HttpClients.createDefault()) {
			log.info("Captcha request: {}", captchaSolution);

			HttpPost httpPost = new HttpPost("https://hcaptcha.com/siteverify");
			List<NameValuePair> params = new ArrayList<NameValuePair>();
			params.add(new BasicNameValuePair("secret", hcaptchaSecret));
			params.add(new BasicNameValuePair("response", captchaSolution.token));
			httpPost.setEntity(new UrlEncodedFormEntity(params));

			CloseableHttpResponse response = client.execute(httpPost);
			JSONObject result = new JSONObject(new JSONTokener(response.getEntity().getContent()));
			client.close();

			log.info("Captcha response: {}", result);
			if (result.getBoolean("success")) {
				Address paymentAddress = cardanoCli.createDisposableAddress();
				addressRepository.save(paymentAddress);
				return new ResponseEntity<AddressResult>(new AddressResult(paymentAddress.getAddress()), HttpStatus.OK);
			} else {
				return new ResponseEntity<AddressResult>(HttpStatus.BAD_REQUEST);
			}
		}
	}

	@GetMapping("tokenAddress")
	public ResponseEntity<String> getAddress(@NotEmpty String assetName, @NotNull Long key) throws Exception {
		Optional<ShopItem> shopItem = shopItemService.getShopItems().stream().filter(e -> e.getAssetName().equals(assetName)).findFirst();
		if (shopItem.isEmpty()) {
			return new ResponseEntity<String>(HttpStatus.NOT_FOUND);
		} else if (!shopItem.get().getMetaData().getString("price").equals(DigestUtils.sha256Hex("" + key))) {
			return new ResponseEntity<String>(HttpStatus.UNAUTHORIZED);
		} else {
			Address findByAssetName = addressRepository.findByAssetName(assetName);
			if (findByAssetName == null) {
				findByAssetName = cardanoCli.createDisposableAddress();
				findByAssetName.setAssetName(assetName);
				addressRepository.save(findByAssetName);
			}
			return new ResponseEntity<String>(findByAssetName.getAddress(), HttpStatus.OK);
		}

	}

	@GetMapping("policyTokens")
	public List<MintedToken> getPolicyTokens() throws DecoderException {
		return cardanoDbSyncClient.policyTokens(nftMinter.getPolicy().getPolicyId());
	}

	@GetMapping("currentHunterValues")
	public List<HunterSnapshotRow> getCurrentHunterValues() {
		return charlyHunterService.getCurrentToplist();
	}

}
