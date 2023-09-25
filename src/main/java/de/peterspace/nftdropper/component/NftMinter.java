package de.peterspace.nftdropper.component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import de.peterspace.cardanodbsyncapi.client.model.TokenDetails;
import de.peterspace.cardanodbsyncapi.client.model.Utxo;
import de.peterspace.nftdropper.cardano.CardanoCli;
import de.peterspace.nftdropper.cardano.CardanoDbSyncClient;
import de.peterspace.nftdropper.cardano.CardanoNode;
import de.peterspace.nftdropper.model.Address;
import de.peterspace.nftdropper.model.Policy;
import de.peterspace.nftdropper.model.TokenData;
import de.peterspace.nftdropper.model.TransactionOutputs;
import de.peterspace.nftdropper.repository.AddressRepository;
import jakarta.annotation.PostConstruct;
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
	private final TaskExecutor taskExecutor;

	private final Cache<String, Boolean> blacklist = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build();

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
		List<Utxo> offerFundings = cardanoDbSyncClient.getUtxos(fundAddress.getAddress());
		Map<String, List<Utxo>> transactionInputGroups = offerFundings.stream()
				.filter(of -> blacklist.getIfPresent(of.getSourceAddress()) == null)
				.collect(Collectors.groupingBy(Utxo::getSourceAddress, LinkedHashMap::new, Collectors.toList()));

		for (List<Utxo> Utxo : transactionInputGroups.values()) {
			try {

				long lockedFunds = calculateLockedFunds(Utxo);
				Optional<Utxo> santoRiverDiggingToken = getSantoRiverDiggingToken(Utxo);

				if (santoRiverDiggingToken.isPresent()) {
					sellsantoRiverDiggingToken(fundAddress, Utxo, santoRiverDiggingToken, lockedFunds);
				}

				else {
					sellGenericToken(fundAddress, Utxo, lockedFunds);
				}

			} catch (Exception e) {
				log.error("Utxo failed to process", e);
			} finally {
				blacklist.put(Utxo.get(0).getSourceAddress(), true);
			}
		}

	}

	private void sellsantoRiverDiggingToken(Address fundAddress, List<Utxo> Utxo, Optional<Utxo> santoRiverDiggingToken, long lockedFunds) throws Exception {
		Map<String, Integer> traitMap = getTraitMap(santoRiverDiggingToken.get());
		long price = getCostTraitFromMap(traitMap) * 1_000_000;

		if (nftSupplier.tokensLeft() == 0) {
			refund(fundAddress, Utxo, lockedFunds);
		} else if (hasEnoughFunds(price, Utxo, lockedFunds)) {
			int min = traitMap.get("Lmin").intValue();
			int max = traitMap.get("Lmax").intValue();
			int amount = min + sr.nextInt((max + 1) - min);
			List<TokenData> tokens = nftSupplier.claimTokens(amount);
			long totalPrice = getCostTraitFromMap(traitMap) * 1_000_000;
			sell(fundAddress, Utxo, tokens, totalPrice, lockedFunds);
		}
	}

	private void sellGenericToken(Address fundAddress, List<Utxo> Utxo, long lockedFunds) throws Exception {
		long minPrice = tierPrices.isEmpty() ? tokenPrice * 1_000_000 : tierPrices.get(0).getAmount() * tierPrices.get(0).getPrice() * 1_000_000;
		int mintsLeft = calculateMintsLeft(fundAddress);

		if (nftSupplier.tokensLeft() == 0 || mintsLeft < 1) {
			refund(fundAddress, Utxo, lockedFunds);
		} else if (hasEnoughFunds(minPrice, Utxo, lockedFunds)) {
			long funds = calculateAvailableFunds(Utxo) - lockedFunds;
			long selectedPrice = findTierPrice(funds / 1_000_000) * 1_000_000;
			int amount = (int) NumberUtils.min(funds / selectedPrice, mintsLeft);
			List<TokenData> tokens = nftSupplier.claimTokens(amount);
			long totalPrice = tokens.size() * selectedPrice;
			increaseMintCounters(fundAddress, tokens);
			sell(fundAddress, Utxo, tokens, totalPrice, lockedFunds);
		}
	}

	private void sell(Address fundAddress, List<Utxo> utxos, List<TokenData> tokens, long totalPrice, long lockedFunds) throws Exception {

		taskExecutor.execute(() -> {
			try {

				String buyerAddress = utxos.get(0).getSourceAddress();

				log.info("selling {} tokens to {} : {}", tokens.size(), buyerAddress, tokens);

				TransactionOutputs transactionOutputs = new TransactionOutputs();

				// send tokens
				for (TokenData token : tokens) {
					transactionOutputs.add(buyerAddress, formatCurrency(policy.getPolicyId(), token.assetName()), 1);
				}

				// return input tokens to seller
				if (utxos.stream().filter(e -> !e.getMaPolicyId().isEmpty()).map(f -> f.getMaPolicyId()).distinct().count() > 0) {
					utxos.stream().filter(e -> !e.getMaPolicyId().isEmpty()).forEach(utxo -> {
						transactionOutputs.add(buyerAddress, formatCurrency(utxo.getMaPolicyId(), utxo.getMaName()), utxo.getValue());
					});
				}

				Optional<Utxo> santoToken = getSantoRiverDiggingToken(utxos);
				if (santoToken.isPresent()) {
					transactionOutputs.add(buyerAddress, formatCurrency(santoToken.get().getMaPolicyId(), santoToken.get().getMaName()), -santoToken.get().getValue());
					transactionOutputs.add(sellerAddress, formatCurrency(santoToken.get().getMaPolicyId(), santoToken.get().getMaName()), santoToken.get().getValue());
					transactionOutputs.add(sellerAddress, "", cardanoCli.calculateMinUtxo(transactionOutputs.toCliFormat(sellerAddress)));
				}

				// min output for tokens
				if (transactionOutputs.getOutputs().containsKey(buyerAddress)) {
					transactionOutputs.add(buyerAddress, "", cardanoCli.calculateMinUtxo(transactionOutputs.toCliFormat(buyerAddress)));
				}

				// return change to buyer
				long change = calculateAvailableFunds(utxos) - lockedFunds - totalPrice;
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
						try (InputStream newInputStream = Files.newInputStream(Paths.get(tokenDir, "sold", tokenData.getFilename()))) {
							metaData.put("image", "ipfs://" + ipfsClient.addFile(newInputStream));
						}
					}
					policyMetadata.put(tokenData.assetName(), metaData);
				}
				JSONObject metaData = new JSONObject().put("721", new JSONObject().put(policy.getPolicyId(), policyMetadata).put("version", "1.0"));

				// submit transaction
				String txId = cardanoCli.mint(utxos, transactionOutputs, metaData, fundAddress, policy, sellerAddress);
				log.info("Successfully sold {} for {}, txid: {}", tokens.size(), totalPrice, txId);
			} catch (Exception e) {
				try {
					nftSupplier.restituteTokens(tokens);
				} catch (IOException e1) {
					log.error("Unable to restitute tokens", e1);
				}
				log.error("sell failed", e);
			}
		});

	}

	private void refund(Address fundAddress, List<Utxo> utxos, long lockedFunds) throws Exception {

		taskExecutor.execute(() -> {
			try {
				// determine amount of tokens
				String buyerAddress = utxos.get(0).getSourceAddress();

				TransactionOutputs transactionOutputs = new TransactionOutputs();

				if (utxos.stream().filter(utxo -> !utxo.getMaPolicyId().isEmpty()).map(f -> f.getMaPolicyId()).distinct().count() > 0) {
					utxos.stream().filter(utxo -> !utxo.getMaPolicyId().isEmpty()).forEach(utxo -> {
						transactionOutputs.add(buyerAddress, formatCurrency(utxo.getMaPolicyId(), utxo.getMaName()), utxo.getValue());
					});
				}
				transactionOutputs.add(buyerAddress, "", lockedFunds);

				String txId = cardanoCli.mint(utxos, transactionOutputs, null, fundAddress, policy, buyerAddress);
				log.info("Successfully refunded, txid: {}", txId);

			} catch (Exception e) {
				log.error("sell failed", e);
			}
		});

	}

	private long calculateAvailableFunds(List<Utxo> Utxo) {
		return Utxo.stream().filter(e -> e.getMaPolicyId().isEmpty()).mapToLong(e -> e.getValue()).sum();
	}

	private long calculateLockedFunds(List<Utxo> utxos) throws Exception {

		if (utxos.stream()
				.filter(s -> !s.getMaPolicyId().isBlank())
				.filter(s -> !s.getMaPolicyId().equals(santoRiverDiggingPolicyId))
				.findAny().isEmpty()) {
			return 0;
		}

		String addressValue = utxos.get(0).getSourceAddress() + " " + utxos.stream()
				.filter(utxo -> !utxo.getMaPolicyId().isBlank())
				.filter(utxo -> !utxo.getMaPolicyId().equals(santoRiverDiggingPolicyId))
				.map(utxo -> (utxo.getValue() + " " + formatCurrency(utxo.getMaPolicyId(), utxo.getMaName())).trim())
				.collect(Collectors.joining("+"));

		return cardanoCli.calculateMinUtxo(addressValue);
	}

	private boolean hasEnoughFunds(long minFunds, List<Utxo> g, long lockedFunds) throws Exception {
		return calculateAvailableFunds(g) - lockedFunds >= (minFunds);
	}

	private int calculateMintsLeft(Address fundAddress) {
		return tokenMaxAmount - (useCaptcha ? fundAddress.getTokensMinted() : 0);
	}

	private void increaseMintCounters(Address fundAddress, List<TokenData> tokens) {
		// count per address
		if (fundAddress != paymentAddress) {
			fundAddress.setTokensMinted(fundAddress.getTokensMinted() + tokens.size());
			addressRepository.save(fundAddress);
			log.info("{} has {} tokens left", fundAddress.getAddress(), tokenMaxAmount - fundAddress.getTokensMinted());
		}
	}

	private Integer getCostTraitFromMap(Map<String, Integer> traitMap) {
		return ObjectUtils.firstNonNull(traitMap.get("Cost"), traitMap.get("C"));
	}

	private Optional<Utxo> getSantoRiverDiggingToken(List<Utxo> g) {
		Optional<Utxo> stantoRiverDiggingToken = g.stream()
				.filter(e -> !StringUtils.isBlank(santoRiverDiggingPolicyId) && Objects.equals(e.getMaPolicyId(), santoRiverDiggingPolicyId))
				.findFirst();
		return stantoRiverDiggingToken;
	}

	private Map<String, Integer> getTraitMap(Utxo e) throws JSONException {
		TokenDetails tokenDetails = cardanoDbSyncClient.getTokenDetails(e.getMaPolicyId(), e.getMaName());
		JSONArray traits = new JSONObject(tokenDetails.getMetadata()).getJSONArray("traits");
		Map<String, Integer> traitMap = new HashMap<>();
		for (int i = 0; i < traits.length(); i++) {
			String[] bits = traits.getString(i).split("=");
			traitMap.put(bits[0], Integer.valueOf(bits[1]));
		}
		return traitMap;
	}

	private String formatCurrency(String policyId, String assetNameHex) {
		if (StringUtils.isBlank(assetNameHex)) {
			return policyId;
		} else {
			return policyId + "." + assetNameHex;
		}
	}

}
