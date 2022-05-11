package de.peterspace.nftdropper.component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import de.peterspace.nftdropper.cardano.CardanoCli;
import de.peterspace.nftdropper.cardano.CardanoDbSyncClient;
import de.peterspace.nftdropper.model.Address;
import de.peterspace.nftdropper.model.TransactionInputs;
import de.peterspace.nftdropper.model.TransactionOutputs;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class CharlySeller {

	private final SecureRandom sr = new SecureRandom();

	@Value("${seller.address}")
	private String sellerAddress;

	@Value("${charly.token}")
	private String charlyToken;
	@Getter
	private String charlyTokenPolicyId;
	@Getter
	private String charlyTokenAssetName;

	@Value("${token.price}")
	private long tokenPrice;

	@Value("${token.dir}")
	private String tokenDir;

	// Injects
	private final CardanoCli cardanoCli;
	private final CardanoDbSyncClient cardanoDbSyncClient;

	@Getter
	private List<CharlyTier> charlyTiers = new ArrayList<>();

	@Getter
	private Address paymentAddress;

	private final Cache<Long, Boolean> blacklist = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build();

	@lombok.Value
	public static class CharlyTier {
		double probability;
		long min;
		long max;
	}

	@PostConstruct
	public void init() throws Exception {

		if (StringUtils.isBlank(charlyToken)) {
			return;
		}

		charlyTokenPolicyId = charlyToken.split("\\.")[0];
		charlyTokenAssetName = charlyToken.split("\\.")[1];
		paymentAddress = cardanoCli.createPaymentAddress();
		Path sourceFolder = Paths.get(tokenDir);
		Path tiersPath = sourceFolder.resolve("charlyTiers.json");
		if (Files.exists(tiersPath)) {
			JSONArray jsonTiers = new JSONArray(new JSONTokener(Files.newBufferedReader(tiersPath)));
			for (int i = 0; i < jsonTiers.length(); i++) {
				JSONObject jsonTier = jsonTiers.getJSONObject(i);
				charlyTiers.add(new CharlyTier(jsonTier.getDouble("probability"), jsonTier.getLong("min"), jsonTier.getLong("max")));
			}
			Collections.sort(charlyTiers, Comparator.comparingDouble(tp -> tp.probability));
			double probabilitySum = charlyTiers.stream().mapToDouble(t -> t.getProbability()).sum();
			if (probabilitySum != 1) {
				throw new RuntimeException("probabilitySum is not 1: " + probabilitySum);
			}
		}
	}

	public long tokensLeft() {
		List<TransactionInputs> offerFundings = cardanoDbSyncClient.getOfferFundings(paymentAddress.getAddress());
		List<TransactionInputs> utxosWithCharlyTokens = getCharlyInputs(offerFundings);
		return countCharlyFunds(utxosWithCharlyTokens);
	}

	@Scheduled(cron = "*/1 * * * * *")
	public void processOffers() throws Exception {

		if (StringUtils.isBlank(charlyToken)) {
			return;
		}

		List<TransactionInputs> offerFundings = cardanoDbSyncClient.getOfferFundings(paymentAddress.getAddress());
		List<TransactionInputs> utxosWithCharlyTokens = getCharlyInputs(offerFundings);
		Map<Long, List<TransactionInputs>> utxosWithoutCharlyTokensGroupedByStakingAddress = getNonCharlyInputsGroupedByStakingAddress(offerFundings);

		// use utxos equally
		Collections.shuffle(offerFundings);
		Collections.shuffle(utxosWithCharlyTokens);

		final long minFunds = tokenPrice * 1_000_000;

		for (List<TransactionInputs> utxosWithoutCharlyTokens : utxosWithoutCharlyTokensGroupedByStakingAddress.values()) {

			try {
				final String buyerAddress = utxosWithoutCharlyTokens.get(0).getSourceAddress();

				long lockedFunds = calculateLockedFunds(utxosWithoutCharlyTokens);
				long totalFunds = calculateAvailableFunds(utxosWithoutCharlyTokens);
				long useableFunds = totalFunds - lockedFunds;

				// check balance
				if (useableFunds < minFunds) {
					continue;
				}

				// calcualte amount
				final long randomAmount = getRandomAmount();

				// gather utxos with charly
				List<TransactionInputs> reservedUtxosWithCharlyTokens = new ArrayList<>();
				while (countCharlyFunds(reservedUtxosWithCharlyTokens) < randomAmount && !utxosWithCharlyTokens.isEmpty()) {
					TransactionInputs reservedUtxo = utxosWithCharlyTokens.get(0);
					List<TransactionInputs> reservedUtxos = utxosWithCharlyTokens.stream().filter(utxo -> utxo.getTxhash().equals(reservedUtxo.getTxhash()) && utxo.getTxix() == reservedUtxo.getTxix()).collect(Collectors.toList());
					utxosWithCharlyTokens.removeAll(reservedUtxos);
					reservedUtxosWithCharlyTokens.addAll(reservedUtxos);
				}
				long gatheredCharlies = countCharlyFunds(reservedUtxosWithCharlyTokens) + countCharlyFunds(utxosWithoutCharlyTokens);
				if (gatheredCharlies < randomAmount) {
					log.info("Not enough charly left, please start next tier, blacklisting utxos {}", utxosWithoutCharlyTokens.get(0).getSourceAddress());
					break;
				}

				TransactionOutputs transactionOutputs = new TransactionOutputs();

				// buyer
				transactionOutputs.add(buyerAddress, formatCurrency(charlyTokenPolicyId, charlyTokenAssetName), randomAmount);

				// return input tokens to seller
				if (utxosWithoutCharlyTokens.stream().filter(e -> !e.getPolicyId().isEmpty()).map(f -> f.getPolicyId()).distinct().count() > 0) {
					utxosWithoutCharlyTokens.stream().filter(e -> !e.getPolicyId().isEmpty()).forEach(i -> {
						transactionOutputs.add(buyerAddress, formatCurrency(i.getPolicyId(), i.getAssetName()), i.getValue());
					});
				}

				// min output for tokens
				transactionOutputs.add(buyerAddress, "", cardanoCli.calculateMinUtxo(transactionOutputs.toCliFormat(buyerAddress)));

				// return change to buyer
				long change = useableFunds - minFunds;
				transactionOutputs.add(buyerAddress, "", change);

				// charly bowl
				long charlyLeft = gatheredCharlies - randomAmount;
				long usedCharlyInputCount = countCharlyInputs(reservedUtxosWithCharlyTokens);
				for (int i = 0; i < usedCharlyInputCount; i++) {
					transactionOutputs.add(paymentAddress.getAddress() + "#" + i, formatCurrency(charlyTokenPolicyId, charlyTokenAssetName), charlyLeft / usedCharlyInputCount);
					transactionOutputs.add(paymentAddress.getAddress() + "#" + i, "", cardanoCli.calculateMinUtxo(transactionOutputs.toCliFormat(paymentAddress.getAddress() + "#" + i)));
				}

				if (charlyLeft % usedCharlyInputCount != 0) {
					transactionOutputs.add(paymentAddress.getAddress() + "#0", formatCurrency(charlyTokenPolicyId, charlyTokenAssetName), charlyLeft % usedCharlyInputCount);
					transactionOutputs.getOutputs().get(paymentAddress.getAddress() + "#0").remove("");
					transactionOutputs.add(paymentAddress.getAddress() + "#0", "", cardanoCli.calculateMinUtxo(transactionOutputs.toCliFormat(paymentAddress.getAddress() + "#0")));
				}

				List allUsedInputs = ListUtils.union(utxosWithoutCharlyTokens, reservedUtxosWithCharlyTokens);
				String txId = cardanoCli.mint(allUsedInputs, transactionOutputs, null, paymentAddress, null, sellerAddress);
				log.info("Successfully sold {} , txid: {}", randomAmount, txId);

			} catch (Exception e) {
				log.error("TransactionInputs failed to process", e);
			} finally {
				blacklist.put(utxosWithoutCharlyTokens.get(0).getStakeAddressId(), true);
			}

		}

	}

	private long calculateAvailableFunds(List<TransactionInputs> transactionInputs) {
		return transactionInputs.stream().filter(e -> e.getPolicyId().isEmpty()).mapToLong(e -> e.getValue()).sum();
	}

	private long calculateLockedFunds(List<TransactionInputs> g) throws Exception {

		if (g.stream().filter(s -> !s.getPolicyId().isBlank()).findAny().isEmpty()) {
			return 0;
		}

		String addressValue = g.get(0).getSourceAddress() + " " + g.stream()
				.filter(s -> !s.getPolicyId().isBlank())
				.map(s -> (s.getValue() + " " + formatCurrency(s.getPolicyId(), s.getAssetName())).trim())
				.collect(Collectors.joining("+"));

		return cardanoCli.calculateMinUtxo(addressValue);
	}

	private long countCharlyFunds(List<TransactionInputs> g) {
		return g.stream()
				.filter(of -> (of.getPolicyId() + "." + of.getAssetName()).equals(charlyToken))
				.mapToLong(e -> e.getValue()).sum();
	}

	private long countCharlyInputs(List<TransactionInputs> g) {
		return g.stream()
				.filter(of -> (of.getPolicyId() + "." + of.getAssetName()).equals(charlyToken))
				.count();
	}

	private long getRandomAmount() {
		double nextDouble = sr.nextDouble();
		double sum = 0;
		for (CharlyTier tier : charlyTiers) {
			sum += tier.getProbability();
			if (sum > nextDouble) {
				return sr.longs(tier.getMin(), tier.getMax() + 1).findFirst().getAsLong();
			}
		}
		throw new RuntimeException("probabilitySum is not 1: " + sum);
	}

	private List<TransactionInputs> getCharlyInputs(List<TransactionInputs> offerFundings) {
		return offerFundings
				.stream()
				.filter(of -> blacklist.getIfPresent(of.getStakeAddressId()) == null)
				// wir wollten nicht nur die charlies sondern auch die zugehörigen adas finden
				.filter(of -> offerFundings.stream().anyMatch(
						check -> (check.getPolicyId() + "." + check.getAssetName()).equals(charlyToken)
								&& check.getTxhash().equals(of.getTxhash())
								&& check.getTxix() == of.getTxix()))
				.collect(Collectors.toList());
	}

	private Map<Long, List<TransactionInputs>> getNonCharlyInputsGroupedByStakingAddress(List<TransactionInputs> offerFundings) {
		return offerFundings
				.stream()
				.filter(of -> blacklist.getIfPresent(of.getStakeAddressId()) == null)
				.filter(of -> !offerFundings.stream().anyMatch(
						check -> (check.getPolicyId() + "." + check.getAssetName()).equals(charlyToken)
								&& check.getTxhash().equals(of.getTxhash())
								&& check.getTxix() == of.getTxix()))
				.collect(Collectors.groupingBy(of -> of.getStakeAddressId(), LinkedHashMap::new, Collectors.toList()));
	}

	private String formatCurrency(String policyId, String assetName) {
		if (StringUtils.isBlank(assetName)) {
			return policyId;
		} else {
			return policyId + "." + Hex.encodeHexString(assetName.getBytes(StandardCharsets.UTF_8));
		}
	}

}
