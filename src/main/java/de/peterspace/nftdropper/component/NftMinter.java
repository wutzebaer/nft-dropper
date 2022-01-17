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
import de.peterspace.nftdropper.repository.AddressRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class NftMinter {

	SecureRandom sr = new SecureRandom();

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
	private final Set<TransactionInputs> blacklist = new HashSet<>();

	@Getter
	private List<TierPrice> tierPrices = new ArrayList<>();

	private Address paymentAddress;
	private Policy policy;

	@lombok.Value
	public static class TierPrice {
		long amount;
		long price;
	}

	@PostConstruct
	public void init() throws Exception {
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

		log.info("Seller Address: {}", sellerAddress);
		log.info("Token Max Amount: {}", tokenMaxAmount);
		log.info("Policy Id: {}", policy.getPolicyId());
		log.info("Token Price: {}", tokenPrice);
		log.info("Tier Prices: {}", tierPrices);
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

		long minFunds = tierPrices.isEmpty() ? tokenPrice : tierPrices.get(0).getAmount() * tierPrices.get(0).getPrice();

		boolean mintsLeft = (tokenMaxAmount - fundAddress.getTokensMinted()) > 0;

		List<List<TransactionInputs>> validTransactionInputGroups = transactionInputGroups
				.values()
				.stream()
				.filter(g -> {
					try {
						return !mintsLeft && getSantoRiverDiggingToken(offerFundings).isEmpty() || nftSupplier.tokensLeft() == 0 || hasEnoughFunds(minFunds, g) || hasEnoughSantoRiverDiggingTokenFunds(g);
					} catch (Exception e) {
						log.error("Input " + g.get(0).getSourceAddress() + " failed for " + fundAddress.getAddress() + " failed", e);
						return false;
					}
				})
				.collect(Collectors.toList());

		for (List<TransactionInputs> validTransactionInputGroup : validTransactionInputGroups) {
			try {
				if (nftSupplier.tokensLeft() > 0 && (mintsLeft || hasEnoughSantoRiverDiggingTokenFunds(validTransactionInputGroup))) {
					sell(fundAddress, validTransactionInputGroup);
				} else {
					refund(fundAddress, validTransactionInputGroup);
				}
				blacklist.addAll(validTransactionInputGroup);
			} catch (Exception e) {
				log.error("Input " + validTransactionInputGroup.get(0).getSourceAddress() + " failed for " + fundAddress.getAddress() + " failed", e);
			}
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
		// determine amount of tokens
		String buyerAddress = transactionInputs.get(0).getSourceAddress();
		long funds = transactionInputs.stream().filter(e -> e.getPolicyId().isEmpty()).mapToLong(e -> e.getValue()).sum();

		Optional<TransactionInputs> santoRiverDiggingToken = getSantoRiverDiggingToken(transactionInputs);

		long selectedPrice;
		int amount;

		if (santoRiverDiggingToken.isPresent()) {
			Map<String, Integer> traitMap = getTraitMap(santoRiverDiggingToken.get());
			selectedPrice = getCostTraitFromMap(traitMap);
			int min = traitMap.get("Lmin").intValue();
			int max = traitMap.get("Lmax").intValue();
			amount = min + sr.nextInt((max + 1) - min);
		} else {
			selectedPrice = findTierPrice(funds / 1_000_000);
			amount = (int) NumberUtils.min(
					funds / (selectedPrice * 1_000_000),
					tokenMaxAmount - (useCaptcha ? fundAddress.getTokensMinted() : 0));
		}
		amount = Math.min(amount, nftSupplier.tokensLeft());

		// select tokens
		List<TokenData> tokens = nftSupplier.getTokens(amount);
		log.info("selling {} tokens to {} : {}", amount, buyerAddress, tokens);

		TransactionOutputs transactionOutputs = new TransactionOutputs();

		// send tokens
		for (TokenData token : tokens) {
			transactionOutputs.add(buyerAddress, formatCurrency(policy.getPolicyId(), token.assetName()), 1);
		}

		// min output for tokens
		long minOutput = 0;
		if (amount > 0) {
			minOutput = MinOutputCalculator.calculate(tokens.stream().map(f -> f.assetName()).collect(Collectors.toSet()), 1);
			transactionOutputs.add(buyerAddress, "", minOutput);
		}

		// if user paid more than needed
		long change;
		if (santoRiverDiggingToken.isPresent()) {
			Map<String, Integer> traitMap = getTraitMap(santoRiverDiggingToken.get());
			change = funds - (getCostTraitFromMap(traitMap) * 1_000_000);
		} else {
			change = funds - amount * (selectedPrice * 1_000_000);
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

		// build metadata
		JSONObject policyMetadata = new JSONObject();
		for (TokenData tokenData : tokens) {
			JSONObject metaData = tokenData.getMetaData();
			try (InputStream newInputStream = Files.newInputStream(Paths.get(tokenDir, tokenData.getFilename()))) {
				metaData.put("image", "ipfs://" + ipfsClient.addFile(newInputStream));
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
		return g.stream().filter(e -> e.getPolicyId().isEmpty()).mapToLong(e -> e.getValue()).sum() >= (minFunds * 1_000_000);
	}

	private boolean hasEnoughSantoRiverDiggingTokenFunds(List<TransactionInputs> g) {
		Optional<TransactionInputs> stantoRiverDiggingToken = getSantoRiverDiggingToken(g);
		if (stantoRiverDiggingToken.isPresent()) {
			Map<String, Integer> traitMap = getTraitMap(stantoRiverDiggingToken.get());
			return hasEnoughFunds(getCostTraitFromMap(traitMap), g);
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
