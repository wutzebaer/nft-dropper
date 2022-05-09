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
		log.info("Token maxAmountWhitelist: {}", tokenMaxAmount);
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

		final long minFunds = calculateMinFunds(fundAddress);
		final boolean mintsLeft = calculateMintsLeft(fundAddress);

		for (List<TransactionInputs> transactionInputGroup : transactionInputGroups.values()) {
			try {
				final boolean walletMintsLeft = whitelist.isEmpty() || walletRepository.findById(transactionInputGroup.get(0).getStakeAddressId()).map(w -> w.getTokensMinted()).orElse(0) < tokenMaxAmount;
				if (!mintsLeft && getSantoRiverDiggingToken(offerFundings).isEmpty()
						|| !walletMintsLeft && getSantoRiverDiggingToken(offerFundings).isEmpty()
						|| nftSupplier.tokensLeft() == 0
						|| (!whitelist.isEmpty() && !whitelist.contains(transactionInputGroup.get(0).getStakeAddressId()))) {
					refund(fundAddress, transactionInputGroup);
					blacklist.addAll(transactionInputGroup);
				} else if (hasEnoughFunds(minFunds, transactionInputGroup) || hasEnoughSantoRiverDiggingTokenFunds(transactionInputGroup)) {
					sell(fundAddress, transactionInputGroup);
					blacklist.addAll(transactionInputGroup);
				}
			} catch (Exception e) {
				log.error("Input " + transactionInputGroup.get(0).getSourceAddress() + " failed for " + fundAddress.getAddress() + " failed", e);
			}
		}

	}

	private boolean calculateMintsLeft(Address fundAddress) {
		if (!StringUtils.isEmpty(fundAddress.getAssetName())) {
			Optional<TokenData> token = nftSupplier.getToken(fundAddress.getAssetName());
			return token.isPresent();
		} else {
			return (tokenMaxAmount - fundAddress.getTokensMinted()) > 0;
		}
	}

	private long calculateMinFunds(Address fundAddress) {
		if (!StringUtils.isEmpty(fundAddress.getAssetName())) {
			Optional<TokenData> token = nftSupplier.getToken(fundAddress.getAssetName());
			if (token.isPresent()) {
				return token.get().getMetaData().getLong("_price");
			} else {
				return 0;
			}
		} else if (!tierPrices.isEmpty()) {
			return tierPrices.get(0).getAmount() * tierPrices.get(0).getPrice() * 1_000_000;
		} else {
			return tokenPrice * 1_000_000;
		}
	}

	private String formatCurrency(String policyId, String assetName) {
		if (StringUtils.isBlank(assetName)) {
			return policyId;
		} else {
			return policyId + "." + Hex.encodeHexString(assetName.getBytes(StandardCharsets.UTF_8));
		}
	}

	private void sell(Address fundAddress, List<TransactionInputs> transactionInputs) throws DecoderException, Exception, IOException {
		String buyerAddress = transactionInputs.get(0).getSourceAddress();
		long funds = transactionInputs.stream().filter(e -> e.getPolicyId().isEmpty()).mapToLong(e -> e.getValue()).sum();
		Optional<TransactionInputs> santoRiverDiggingToken = getSantoRiverDiggingToken(transactionInputs);
		Optional<TokenData> addressToken = nftSupplier.getToken(fundAddress.getAssetName());
		Optional<Wallet> wallet = Optional.empty();

		// determine amount of tokens and price
		int amount;
		long selectedPrice;
		if (addressToken.isPresent()) {
			amount = 1;
			selectedPrice = addressToken.get().getMetaData().getLong("_price");
		}
		if (santoRiverDiggingToken.isPresent()) {
			Map<String, Integer> traitMap = getTraitMap(santoRiverDiggingToken.get());
			selectedPrice = getCostTraitFromMap(traitMap) * 1_000_000;
			int min = traitMap.get("Lmin").intValue();
			int max = traitMap.get("Lmax").intValue();
			amount = min + sr.nextInt((max + 1) - min);
		} else {
			selectedPrice = findTierPrice(funds / 1_000_000) * 1_000_000;
			amount = (int) NumberUtils.min(
					funds / selectedPrice,
					tokenMaxAmount - (useCaptcha ? fundAddress.getTokensMinted() : 0));
		}
		if (!whitelist.isEmpty()) {
			wallet = walletRepository.findById(transactionInputs.get(0).getStakeAddressId());
			if (wallet.isEmpty()) {
				wallet = Optional.of(new Wallet(transactionInputs.get(0).getStakeAddressId(), 0));
			}
			int walletMintsLeft = tokenMaxAmount - wallet.get().getTokensMinted();
			amount = Math.min(amount, walletMintsLeft);
		}
		amount = Math.min(amount, nftSupplier.tokensLeft());

		// select tokens
		List<TokenData> tokens = addressToken.isPresent() ? List.of(addressToken.get()) : nftSupplier.getTokens(amount);
		log.info("selling {} tokens to {} : {}", amount, buyerAddress, tokens);

		TransactionOutputs transactionOutputs = new TransactionOutputs();

		// send tokens
		for (TokenData token : tokens) {
			transactionOutputs.add(buyerAddress, formatCurrency(policy.getPolicyId(), token.assetName()), 1);
		}
		if (santoRiverDiggingToken.isPresent()) {
			transactionOutputs.add(buyerAddress, formatCurrency(santoRiverDiggingToken.get().getPolicyId(), santoRiverDiggingToken.get().getAssetName()), 1);
		}

		// min output for tokens
		long minOutput = 0;
		if (amount > 0 || santoRiverDiggingToken.isPresent()) {
			long policyCount = transactionOutputs.getOutputs().get(buyerAddress).keySet().stream().map(s -> s.split("\\.")[0]).distinct().count();
			Set<String> assetNames = transactionOutputs.getOutputs().get(buyerAddress).keySet().stream().map(s -> s.split("\\.")).filter(a -> a.length > 1).map(a -> a[1]).collect(Collectors.toSet());
			minOutput = MinOutputCalculator.calculate(assetNames, policyCount);
			transactionOutputs.add(buyerAddress, "", minOutput);
		}

		// if user paid more than needed
		long change;
		if (santoRiverDiggingToken.isPresent()) {
			Map<String, Integer> traitMap = getTraitMap(santoRiverDiggingToken.get());
			change = funds - (getCostTraitFromMap(traitMap) * 1_000_000);
		} else {
			change = funds - amount * (selectedPrice);
		}
		if (change > 0) {
			transactionOutputs.add(buyerAddress, "", change);
		}

		if (donate) {
			transactionOutputs.add(cardanoNode.getDonationAddress(), "", 1_000_000);
		}

		// send input tokens to seller
		if (transactionInputs.stream().filter(e -> !e.getPolicyId().isEmpty()).map(f -> f.getPolicyId()).distinct().count() > 0) {
			transactionInputs.stream().filter(e -> !e.getPolicyId().isEmpty()).forEach(i -> {
				transactionOutputs.add(sellerAddress, formatCurrency(i.getPolicyId(), i.getAssetName()), i.getValue());
			});
		}
		// remove token from seller, be cause it is added to sender
		if (santoRiverDiggingToken.isPresent()) {
			transactionOutputs.add(sellerAddress, formatCurrency(santoRiverDiggingToken.get().getPolicyId(), santoRiverDiggingToken.get().getAssetName()), -1);
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

		transactionOutputs.add(sellerAddress, "", 1_000_000);

		try {
			long fees = cardanoCli.calculateFee(transactionInputs, transactionOutputs, metaData, fundAddress, policy);
			transactionOutputs.add(sellerAddress, "", -1_000_000);
			transactionOutputs.add(sellerAddress, "", funds - change - fees - minOutput - (donate ? 1_000_000 : 0));

			String txId = cardanoCli.mint(transactionInputs, transactionOutputs, metaData, fundAddress, policy, fees);
			log.info("Successfully sold {} for {}, txid: {}", amount, selectedPrice, txId);
			nftSupplier.markTokenSold(tokens);

			if (fundAddress != paymentAddress) {
				fundAddress.setTokensMinted(fundAddress.getTokensMinted() + amount);
				addressRepository.save(fundAddress);
				log.info("{} has {} tokens left", fundAddress.getAddress(), tokenMaxAmount - fundAddress.getTokensMinted());
			}

			if (wallet.isPresent()) {
				wallet.get().setTokensMinted(wallet.get().getTokensMinted() + amount);
				walletRepository.save(wallet.get());
			}

		} catch (UnexpectedTokensException e) {
			log.error("User {} sent tokens, blacklisting: {}", buyerAddress, e.getMessage());
		} catch (UnprocessedTransactionsException e) {
			log.error("User {} has unprocessed transactions", buyerAddress, e.getMessage());
		} catch (OutputTooSmallUTxOException e) {
			log.error("User {} has send tokens with to few ada", buyerAddress, e.getMessage());
			blacklist.addAll(transactionInputs);
		}
	}

	private void refund(Address fundAddress, List<TransactionInputs> transactionInputs) throws Exception {
		// determine amount of tokens
		String buyerAddress = transactionInputs.get(0).getSourceAddress();

		long funds = transactionInputs.stream().filter(e -> e.getPolicyId().isEmpty()).mapToLong(e -> e.getValue()).sum();

		TransactionOutputs transactionOutputs = new TransactionOutputs();

		if (transactionInputs.stream().filter(e -> !e.getPolicyId().isEmpty()).map(f -> f.getPolicyId()).distinct().count() > 0) {
			transactionInputs.stream().filter(e -> !e.getPolicyId().isEmpty()).forEach(i -> {
				transactionOutputs.add(buyerAddress, formatCurrency(i.getPolicyId(), i.getAssetName()), i.getValue());
			});
		}

		transactionOutputs.add(buyerAddress, "", 1_000_000);

		try {
			long fees = cardanoCli.calculateFee(transactionInputs, transactionOutputs, null, fundAddress, policy);
			transactionOutputs.getOutputs().get(buyerAddress).put("", funds - fees);
			String txId = cardanoCli.mint(transactionInputs, transactionOutputs, null, fundAddress, policy, fees);
			log.info("Successfully refunded, txid: {}", txId);
		} catch (UnexpectedTokensException e) {
			log.error("User {} sent tokens, blacklisting: {}", buyerAddress, e.getMessage());
		} catch (UnprocessedTransactionsException e) {
			log.error("User {} has unprocessed transactions", buyerAddress, e.getMessage());
		} catch (OutputTooSmallUTxOException e) {
			log.error("User {} has send tokens with to few ada", buyerAddress, e.getMessage());
			blacklist.addAll(transactionInputs);
		}
	}

	private boolean hasEnoughFunds(long minFunds, List<TransactionInputs> g) {
		return g.stream().filter(e -> e.getPolicyId().isEmpty()).mapToLong(e -> e.getValue()).sum() >= (minFunds);
	}

	private boolean hasEnoughSantoRiverDiggingTokenFunds(List<TransactionInputs> g) {
		Optional<TransactionInputs> stantoRiverDiggingToken = getSantoRiverDiggingToken(g);
		if (stantoRiverDiggingToken.isPresent()) {
			Map<String, Integer> traitMap = getTraitMap(stantoRiverDiggingToken.get());
			return hasEnoughFunds(getCostTraitFromMap(traitMap) * 1_000_000, g);
		} else {
			return false;
		}
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

}
