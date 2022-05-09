package de.peterspace.nftdropper.component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import de.peterspace.nftdropper.cardano.CardanoCli;
import de.peterspace.nftdropper.cardano.CardanoDbSyncClient;
import de.peterspace.nftdropper.cardano.CardanoNode;
import de.peterspace.nftdropper.cardano.exceptions.OutputTooSmallUTxOException;
import de.peterspace.nftdropper.cardano.exceptions.UnexpectedTokensException;
import de.peterspace.nftdropper.cardano.exceptions.UnprocessedTransactionsException;
import de.peterspace.nftdropper.model.Address;
import de.peterspace.nftdropper.model.Policy;
import de.peterspace.nftdropper.model.TokenData;
import de.peterspace.nftdropper.model.TransactionInputs;
import de.peterspace.nftdropper.model.TransactionOutputs;
import de.peterspace.nftdropper.model.Wallet;
import de.peterspace.nftdropper.repository.AddressRepository;
import de.peterspace.nftdropper.repository.WalletRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class NftMinter {

	SecureRandom sr = new SecureRandom();

	@Value("${charly.token}")
	private String charlyToken;

	@Value("${use.captcha}")
	private boolean useCaptcha;

	@Value("${token.dir}")
	private String tokenDir;

	@Value("${seller.address}")
	private String sellerAddress;

	@Value("${token.price}")
	private long tokenPrice;

	@Value("${token.maxAmount}")
	private int tokenMaxAmount;

	@Value("${donate}")
	private boolean donate;

	@Value("${santo.riverDigging.policyId}")
	private String santoRiverDiggingPolicyId;

	private final CardanoCli cardanoCli;
	private final CardanoNode cardanoNode;
	private final CardanoDbSyncClient cardanoDbSyncClient;
	private final NftSupplier nftSupplier;
	private final IpfsClient ipfsClient;
	private final AddressRepository addressRepository;
	private final WalletRepository walletRepository;
	private final Set<TransactionInputs> blacklist = new HashSet<>();
	private final Set<Long> whitelist = new HashSet<>();

	@Getter
	private List<TierPrice> tierPrices = new ArrayList<>();

	private Address paymentAddress;

	@Getter
	private Policy policy;

	@lombok.Value
	public static class TierPrice {
		long amount;
		long price;
	}

	@PostConstruct
	public void init() throws Exception {

		if (!StringUtils.isBlank(charlyToken)) {
			return;
		}

		paymentAddress = cardanoCli.createPaymentAddress();
		policy = cardanoCli.createPolicy(365);

		Path sourceFolder = Paths.get(tokenDir);
		Path pricesPath = sourceFolder.resolve("prices.json");
		if (Files.exists(pricesPath)) {
			JSONObject prices = new JSONObject(new JSONTokener(Files.newBufferedReader(pricesPath)));
			for (String amount : prices.keySet()) {
				tierPrices.add(new TierPrice(Long.parseLong(amount), prices.getLong(amount)));
			}
			Collections.sort(tierPrices, Comparator.comparingLong(tp -> tp.amount));
		}

		Path whitelistPath = sourceFolder.resolve("whitelist");
		if (Files.exists(whitelistPath)) {
			List<String> whitelistLines = Files.readAllLines(whitelistPath);
			whitelistLines.replaceAll(String::trim);
			Set<Long> findStakeAddressIds = cardanoDbSyncClient.findStakeAddressIds(whitelistLines.toArray(new String[] {}));
			this.whitelist.addAll(findStakeAddressIds);
			log.info("Whitelist: {}", whitelistLines);
			log.info("WhitelistIds: {}", whitelist);
		}

		log.info("Seller Address: {}", sellerAddress);
		log.info("Token Max Amount: {}", tokenMaxAmount);
		log.info("Policy Id: {}", policy.getPolicyId());
		log.info("Token Price: {}", tokenPrice);
		log.info("Tier Prices: {}", tierPrices);
		log.info("Donate: {}", donate);
	}

	private long findTierPrice(long ada) {
		return tierPrices
				.stream()
				.filter(tp -> ada / tp.price >= tp.amount)
				.mapToLong(tp -> tp.price)
				.min()
				.orElseGet(() -> tokenPrice);
	}

	public String getPaymentAddress() {
		return paymentAddress.getAddress();
	}

	@Scheduled(cron = "*/1 * * * * *")
	public void processOffers() throws Exception {

		if (!StringUtils.isBlank(charlyToken)) {
			return;
		}

		if (!useCaptcha) {
			processAddress(paymentAddress);
		}
		addressRepository.findAll().forEach(fundAddress -> {
			processAddress(fundAddress);
		});
	}

	private void processAddress(Address fundAddress) {
		List<TransactionInputs> offerFundings = cardanoDbSyncClient.getOfferFundings(fundAddress.getAddress());
		Map<Long, List<TransactionInputs>> transactionInputGroups = offerFundings.stream()
				.filter(of -> !blacklist.contains(of))
				.collect(Collectors.groupingBy(of -> of.getStakeAddressId(), LinkedHashMap::new, Collectors.toList()));

		for (List<TransactionInputs> transactionInputGroup : transactionInputGroups.values()) {
			try {

				long lockedFunds = calculateLockedAda(transactionInputGroup);
				Optional<TransactionInputs> santoRiverDiggingToken = getSantoRiverDiggingToken(transactionInputGroup);

				if (santoRiverDiggingToken.isPresent()) {
					sellsantoRiverDiggingToken(fundAddress, transactionInputGroup, santoRiverDiggingToken, lockedFunds);
				}

				else if (!StringUtils.isEmpty(fundAddress.getAssetName())) {
					sellAddressToken(fundAddress, transactionInputGroup, lockedFunds);
				}

				else {
					sellGenericToken(fundAddress, transactionInputGroup, lockedFunds);
				}

			} catch (Exception e) {
				log.error("TransactionInputs failed to process, blacklisting", e);
				blacklist.addAll(transactionInputGroup);
			}
		}

	}

	private void sellsantoRiverDiggingToken(Address fundAddress, List<TransactionInputs> transactionInputs, Optional<TransactionInputs> santoRiverDiggingToken, long lockedFunds) throws Exception {
		Map<String, Integer> traitMap = getTraitMap(santoRiverDiggingToken.get());
		long price = getCostTraitFromMap(traitMap) * 1_000_000;

		if (nftSupplier.tokensLeft() == 0) {
			refund(fundAddress, transactionInputs);
		} else if (hasEnoughFunds(price, transactionInputs, lockedFunds)) {
			int min = traitMap.get("Lmin").intValue();
			int max = traitMap.get("Lmax").intValue();
			int amount = min + sr.nextInt((max + 1) - min);
			List<TokenData> tokens = nftSupplier.claimTokens(amount);
			long totalPrice = getCostTraitFromMap(traitMap) * 1_000_000;
			sell2(fundAddress, transactionInputs, tokens, totalPrice, lockedFunds);
		}
	}

	private void sellAddressToken(Address fundAddress, List<TransactionInputs> transactionInputs, long lockedFunds) throws Exception {
		Optional<TokenData> addressToken = nftSupplier.getToken(fundAddress.getAssetName());

		if (addressToken.isEmpty()) {
			refund(fundAddress, transactionInputs);
		} else {
			long price = addressToken.get().getMetaData().getLong("_price");
			if (hasEnoughFunds(price, transactionInputs, lockedFunds)) {
				nftSupplier.removeTokenFromSale(List.of(addressToken.get()));
				long totalPrice = addressToken.get().getMetaData().getLong("_price");
				sell2(fundAddress, transactionInputs, List.of(addressToken.get()), totalPrice, lockedFunds);
			}
		}
	}

	private void sellGenericToken(Address fundAddress, List<TransactionInputs> transactionInputs, long lockedFunds) throws Exception {
		long minPrice = tierPrices.isEmpty() ? tokenPrice * 1_000_000 : tierPrices.get(0).getAmount() * tierPrices.get(0).getPrice() * 1_000_000;

		if (nftSupplier.tokensLeft() == 0) {
			refund(fundAddress, transactionInputs);
		} else if (hasEnoughFunds(minPrice, transactionInputs, lockedFunds)) {
			long funds = calculateAvailableFunds(transactionInputs) - lockedFunds;
			long selectedPrice = findTierPrice(funds / 1_000_000) * 1_000_000;
			int amount = (int) NumberUtils.min(
					funds / selectedPrice,
					tokenMaxAmount - (useCaptcha ? fundAddress.getTokensMinted() : 0));
			List<TokenData> tokens = nftSupplier.claimTokens(amount);
			long totalPrice = tokens.size() * selectedPrice;
			sell2(fundAddress, transactionInputs, tokens, totalPrice, lockedFunds);
		}
	}

	private void sell2(Address fundAddress, List<TransactionInputs> transactionInputs, List<TokenData> tokens, long totalPrice, long lockedFunds) throws Exception {
		String buyerAddress = transactionInputs.get(0).getSourceAddress();
		Optional<Wallet> wallet = Optional.empty();

		log.info("selling {} tokens to {} : {}", tokens.size(), buyerAddress, tokens);

		TransactionOutputs transactionOutputs = new TransactionOutputs();

		// send tokens
		for (TokenData token : tokens) {
			transactionOutputs.add(buyerAddress, formatCurrency(policy.getPolicyId(), token.assetName()), 1);
		}

		// return input tokens to seller
		if (transactionInputs.stream().filter(e -> !e.getPolicyId().isEmpty()).map(f -> f.getPolicyId()).distinct().count() > 0) {
			transactionInputs.stream().filter(e -> !e.getPolicyId().isEmpty()).forEach(i -> {
				transactionOutputs.add(buyerAddress, formatCurrency(i.getPolicyId(), i.getAssetName()), i.getValue());
			});
		}

		// min output for tokens
		transactionOutputs.add(buyerAddress, "", cardanoCli.calculateMinUtxo(transactionOutputs.toCliFormat(buyerAddress)));

		// return change to buyer
		long change = calculateAvailableFunds(transactionInputs) - lockedFunds - totalPrice;
		transactionOutputs.add(buyerAddress, "", change);

		if (donate) {
			transactionOutputs.add(cardanoNode.getDonationAddress(), "", 1_000_000);
		}

		// build metadata
		JSONObject policyMetadata = new JSONObject();
		for (TokenData tokenData : tokens) {
			JSONObject metaData = new JSONObject(tokenData.getMetaData().toString());
			Iterator<String> keys = metaData.keys();
			while (keys.hasNext()) {
				if (keys.next().startsWith("_")) {
					keys.remove();
				}
			}
			if (!metaData.has("image")) {
				try (InputStream newInputStream = Files.newInputStream(Paths.get(tokenDir, tokenData.getFilename()))) {
					metaData.put("image", "ipfs://" + ipfsClient.addFile(newInputStream));
				}
			}
			policyMetadata.put(tokenData.assetName(), metaData);
		}
		JSONObject metaData = new JSONObject().put("721", new JSONObject().put(policy.getPolicyId(), policyMetadata).put("version", "1.0"));

		// submit transaction
		String txId = cardanoCli.mint(transactionInputs, transactionOutputs, metaData, fundAddress, policy, sellerAddress);
		log.info("Successfully sold {} for {}, txid: {}", tokens.size(), totalPrice, txId);

		// count per address
		if (fundAddress != paymentAddress) {
			fundAddress.setTokensMinted(fundAddress.getTokensMinted() + tokens.size());
			addressRepository.save(fundAddress);
			log.info("{} has {} tokens left", fundAddress.getAddress(), tokenMaxAmount - fundAddress.getTokensMinted());
		}

		// count per wallet
		if (wallet.isPresent()) {
			wallet.get().setTokensMinted(wallet.get().getTokensMinted() + tokens.size());
			walletRepository.save(wallet.get());
		}

		blacklist.addAll(transactionInputs);

	}

	private long calculateAvailableFunds(List<TransactionInputs> transactionInputs) {
		return transactionInputs.stream().filter(e -> e.getPolicyId().isEmpty()).mapToLong(e -> e.getValue()).sum();
	}

	private void refund(Address fundAddress, List<TransactionInputs> transactionInputs) throws Exception {
		// determine amount of tokens
		String buyerAddress = transactionInputs.get(0).getSourceAddress();

		TransactionOutputs transactionOutputs = new TransactionOutputs();

		if (transactionInputs.stream().filter(e -> !e.getPolicyId().isEmpty()).map(f -> f.getPolicyId()).distinct().count() > 0) {
			transactionInputs.stream().filter(e -> !e.getPolicyId().isEmpty()).forEach(i -> {
				transactionOutputs.add(buyerAddress, formatCurrency(i.getPolicyId(), i.getAssetName()), i.getValue());
			});
		}

		String txId = cardanoCli.mint(transactionInputs, transactionOutputs, null, fundAddress, policy, buyerAddress);
		log.info("Successfully refunded, txid: {}", txId);

		blacklist.addAll(transactionInputs);
	}

	private long calculateLockedAda(List<TransactionInputs> g) throws Exception {

		if (g.stream().filter(s -> !s.getPolicyId().isBlank()).filter(s -> !s.getPolicyId().equals(santoRiverDiggingPolicyId)).findAny().isEmpty()) {
			return 0;
		}

		String addressValue = g.get(0).getSourceAddress() + " " + g.stream()
				.filter(s -> !s.getPolicyId().isBlank())
				.filter(s -> !s.getPolicyId().equals(santoRiverDiggingPolicyId))
				.map(s -> (s.getValue() + " " + formatCurrency(s.getPolicyId(), s.getAssetName())).trim())
				.collect(Collectors.joining("+"));

		return cardanoCli.calculateMinUtxo(addressValue);
	}

	private boolean hasEnoughFunds(long minFunds, List<TransactionInputs> g, long lockedFunds) throws Exception {
		return calculateAvailableFunds(g) - lockedFunds >= (minFunds);
	}

	private Integer getCostTraitFromMap(Map<String, Integer> traitMap) {
		return ObjectUtils.firstNonNull(traitMap.get("Cost"), traitMap.get("C"));
	}

	private Optional<TransactionInputs> getSantoRiverDiggingToken(List<TransactionInputs> g) {
		Optional<TransactionInputs> stantoRiverDiggingToken = g.stream()
				.filter(e -> !StringUtils.isBlank(santoRiverDiggingPolicyId) && Objects.equals(e.getPolicyId(), santoRiverDiggingPolicyId))
				.findFirst();
		return stantoRiverDiggingToken;
	}

	private Map<String, Integer> getTraitMap(TransactionInputs e) throws JSONException {
		JSONArray traits = new JSONObject(e.getMetaData()).getJSONArray("traits");
		Map<String, Integer> traitMap = new HashMap<>();
		for (int i = 0; i < traits.length(); i++) {
			String[] bits = traits.getString(i).split("=");
			traitMap.put(bits[0], Integer.valueOf(bits[1]));
		}
		return traitMap;
	}

	private String formatCurrency(String policyId, String assetName) {
		if (StringUtils.isBlank(assetName)) {
			return policyId;
		} else {
			return policyId + "." + Hex.encodeHexString(assetName.getBytes(StandardCharsets.UTF_8));
		}
	}

}
