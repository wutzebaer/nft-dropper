package de.peterspace.nftdropper.component;

import static java.util.Map.entry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.codec.binary.Hex;
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
import de.peterspace.nftdropper.model.CharlyJackpotCounter;
import de.peterspace.nftdropper.model.TransactionInputs;
import de.peterspace.nftdropper.model.TransactionOutputs;
import de.peterspace.nftdropper.repository.CharlyJackpotCounterRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class CharlySeller {

	@lombok.Value
	public static class JackpotCoin {
		String assetName;
		Long charlyTokens;
	}

	private Map<Integer, JackpotCoin> jackpot = Map.ofEntries(
			entry(57, new JackpotCoin("CopperJackpotCoin007", 25000000l)),
			entry(114, new JackpotCoin("CopperJackpotCoin008", 25000000l)),
			entry(171, new JackpotCoin("CopperJackpotCoin009", 25000000l)),
			entry(228, new JackpotCoin("CopperJackpotCoin010", 25000000l)),
			entry(285, new JackpotCoin("CopperJackpotCoin011", 25000000l)),
			entry(342, new JackpotCoin("SilverJackpotCoin002", 50000000l)),
			entry(399, new JackpotCoin("SilverJackpotCoin003", 50000000l)),
			entry(456, new JackpotCoin("CopperJackpotCoin012", 25000000l)),
			entry(513, new JackpotCoin("CopperJackpotCoin013", 25000000l)),
			entry(570, new JackpotCoin("SilverJackpotCoin004", 50000000l)),
			entry(627, new JackpotCoin("CopperJackpotCoin014", 25000000l)),
			entry(684, new JackpotCoin("CopperJackpotCoin015", 25000000l)),
			entry(741, new JackpotCoin("SilverJackpotCoin005", 50000000l)),
			entry(798, new JackpotCoin("GoldJackpotCoin001", 100000000l)),
			entry(855, new JackpotCoin("CopperJackpotCoin016", 25000000l)),
			entry(912, new JackpotCoin("SilverJackpotCoin006", 50000000l)),
			entry(969, new JackpotCoin("CopperJackpotCoin017", 25000000l))

	);

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

	@Value("${charly.jackpot.policy}")
	private String jackpotPolicy;

	// Injects
	private final CardanoCli cardanoCli;
	private final CardanoDbSyncClient cardanoDbSyncClient;
	private final CharlyJackpotCounterRepository charlyJackpotCounterRepository;
	private final CharlyHunter2Service charlyHunter2Service;

	private CharlyJackpotCounter charlyJackpotCounter;

	@Getter
	private List<CharlyTier> charlyTiers = new ArrayList<>();

	@Getter
	private Address paymentAddress;

	private final Cache<Long, Boolean> blacklist = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build();
	private final Cache<String, Boolean> blacklistCharly = CacheBuilder.newBuilder().expireAfterWrite(60, TimeUnit.MINUTES).build();

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

		charlyJackpotCounter = charlyJackpotCounterRepository.findById(1l).orElse(new CharlyJackpotCounter());

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

	@Scheduled(cron = "*/10 * * * * *")
	public void processOffers() throws Exception {

		if (StringUtils.isBlank(charlyToken)) {
			return;
		}

		List<TransactionInputs> offerFundings = cardanoDbSyncClient.getOfferFundings(paymentAddress.getAddress());
		List<TransactionInputs> charlyUtxos = getCharlyInputs(offerFundings);
		Map<Long, List<TransactionInputs>> buyerUtxosGroupedByStakingAddress = getPaymentInputs(offerFundings);

		// use utxos equally
		Collections.shuffle(offerFundings);
		Collections.shuffle(charlyUtxos);

		final long minFunds = tokenPrice * 1_000_000;

		for (List<TransactionInputs> buyerUtxos : buyerUtxosGroupedByStakingAddress.values()) {

			try {
				final String buyerAddress = buyerUtxos.get(0).getSourceAddress();

				long lockedFunds = calculateLockedFunds(buyerUtxos);
				long totalFunds = calculateAvailableFunds(buyerUtxos);
				long useableFunds = totalFunds - lockedFunds;

				// check balance
				if (useableFunds < minFunds) {
					continue;
				}

				int amount = (int) (useableFunds / minFunds);
				amount = Math.min(amount, 10);

				// calcualte amount
				final List<Long> randomAmounts = new ArrayList<>();
				for (long i = 0; i < amount; i++) {
					randomAmounts.add(getRandomAmount());
				}
				long totalAmount = randomAmounts.stream().mapToLong(i -> i).sum();

				List<TransactionInputs> allUsedInputs = new ArrayList<>();
				allUsedInputs.addAll(buyerUtxos);

				TransactionOutputs transactionOutputs = new TransactionOutputs();

				// check if JACKPOT input is needed
				if (Instant.now().isAfter(charlyHunter2Service.getHunterStartTimestamp())) {
					for (int i = 0; i < amount; i++) {
						Integer currentTxNum = charlyJackpotCounter.getCount() + 1;
						log.info("Jackpot num: " + currentTxNum);
						JackpotCoin jackpotCoin = jackpot.get(currentTxNum);
						if (jackpotCoin != null) {
							log.info("HIT JACKPOT: " + jackpotCoin);
							// use inputs
							List<TransactionInputs> jackpotInputs = getJackpotInputs(offerFundings);
							allUsedInputs.addAll(jackpotInputs);

							// send nft and jackpot charlies
							transactionOutputs.add(buyerAddress + "#jackpot", formatCurrency(charlyTokenPolicyId, charlyTokenAssetName), jackpotCoin.getCharlyTokens());
							transactionOutputs.add(buyerAddress + "#jackpot", formatCurrency(jackpotPolicy, jackpotCoin.getAssetName()), 1);

							// increae amount to gaster
							totalAmount += jackpotCoin.getCharlyTokens();

							// return other nfts
							jackpotInputs.removeIf(in -> Objects.equals(in.getAssetName(), jackpotCoin.getAssetName()));
							jackpotInputs.removeIf(in -> StringUtils.isBlank(in.getPolicyId()));
							for (TransactionInputs jackpotCoinInput : jackpotInputs) {
								transactionOutputs.add(paymentAddress.getAddress() + "#jackpot", formatCurrency(jackpotCoinInput.getPolicyId(), jackpotCoinInput.getAssetName()), jackpotCoinInput.getValue());
								blacklistCharly.put(jackpotCoinInput.getTxhash() + "#" + jackpotCoinInput.getTxix(), true);
							}
						}
						charlyJackpotCounter.setCount(currentTxNum);
						charlyJackpotCounterRepository.save(charlyJackpotCounter);
					}
				} else {
					charlyJackpotCounter.setCount(0);
					charlyJackpotCounterRepository.save(charlyJackpotCounter);
				}

				// gather charlyToken inputs
				List<TransactionInputs> reservedCharlyUtxos = new ArrayList<>();
				while (countCharlyFunds(reservedCharlyUtxos) < totalAmount && !charlyUtxos.isEmpty()) {
					TransactionInputs reservedUtxo = charlyUtxos.get(0);
					List<TransactionInputs> reservedUtxos = charlyUtxos.stream().filter(utxo -> utxo.getTxhash().equals(reservedUtxo.getTxhash()) && utxo.getTxix() == reservedUtxo.getTxix()).collect(Collectors.toList());
					charlyUtxos.removeAll(reservedUtxos);
					reservedCharlyUtxos.addAll(reservedUtxos);
				}
				long gatheredCharlies = countCharlyFunds(reservedCharlyUtxos) + countCharlyFunds(buyerUtxos);
				if (gatheredCharlies < totalAmount) {
					log.info("Not enough charly left, please start next tier, blacklisting utxos {}", buyerUtxos.get(0).getSourceAddress());
					break;
				}
				allUsedInputs.addAll(reservedCharlyUtxos);

				// buyer
				for (int i = 0; i < amount; i++) {
					transactionOutputs.add(buyerAddress + "#" + i, formatCurrency(charlyTokenPolicyId, charlyTokenAssetName), randomAmounts.get(i));
				}
				// return change to buyer
				long change = useableFunds - (minFunds * amount);
				transactionOutputs.add(buyerAddress, "", change);
				// when the buyer sent tokens for some reason, end them back
				List<TransactionInputs> buyersSentTokens = buyerUtxos.stream().filter(e -> !e.getPolicyId().isEmpty()).collect(Collectors.toList());
				if (!buyersSentTokens.isEmpty()) {
					for (TransactionInputs buyerSentToken : buyersSentTokens) {
						transactionOutputs.add(buyerAddress, formatCurrency(buyerSentToken.getPolicyId(), buyerSentToken.getAssetName()), buyerSentToken.getValue());
					}
				}

				// distribute remaining charlies to the same amount of utxos
				long charlyLeft = gatheredCharlies - totalAmount;
				long usedCharlyInputCount = countCharlyInputs(reservedCharlyUtxos);
				for (int i = 0; i < usedCharlyInputCount; i++) {
					transactionOutputs.add(paymentAddress.getAddress() + "#" + i, formatCurrency(charlyTokenPolicyId, charlyTokenAssetName), charlyLeft / usedCharlyInputCount);
				}
				if (charlyLeft % usedCharlyInputCount != 0) {
					transactionOutputs.add(paymentAddress.getAddress() + "#0", formatCurrency(charlyTokenPolicyId, charlyTokenAssetName), charlyLeft % usedCharlyInputCount);
				}

				// min output
				for (String address : transactionOutputs.getOutputs().keySet()) {
					transactionOutputs.add(address, "", cardanoCli.calculateMinUtxo(transactionOutputs.toCliFormat(address)));
				}

				String txId = cardanoCli.mint(allUsedInputs, transactionOutputs, null, paymentAddress, null, sellerAddress);

				for (TransactionInputs usedInput : allUsedInputs) {
					blacklistCharly.put(usedInput.getTxhash() + "#" + usedInput.getTxix(), true);
				}

				log.info("Successfully sold {} , txid: {}", randomAmounts, txId);

			} catch (Exception e) {
				log.error("TransactionInputs failed to process", e);
			} finally {
				blacklist.put(buyerUtxos.get(0).getStakeAddressId(), true);
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
				.filter(of -> blacklistCharly.getIfPresent(of.getTxhash() + "#" + of.getTxix()) == null)
				.filter(isCharlyInput(offerFundings))
				.collect(Collectors.toList());
	}

	private List<TransactionInputs> getJackpotInputs(List<TransactionInputs> offerFundings) {
		return offerFundings
				.stream()
				.filter(of -> blacklistCharly.getIfPresent(of.getTxhash() + "#" + of.getTxix()) == null)
				.filter(isJackpotInput(offerFundings))
				.collect(Collectors.toList());
	}

	private Map<Long, List<TransactionInputs>> getPaymentInputs(List<TransactionInputs> offerFundings) {
		return offerFundings
				.stream()
				.filter(of -> blacklist.getIfPresent(of.getStakeAddressId()) == null)
				.filter(isCharlyInput(offerFundings).negate())
				.filter(isJackpotInput(offerFundings).negate())
				.collect(Collectors.groupingBy(of -> of.getStakeAddressId(), LinkedHashMap::new, Collectors.toList()));
	}

	private String formatCurrency(String policyId, String assetName) {
		if (StringUtils.isBlank(assetName)) {
			return policyId;
		} else {
			return policyId + "." + Hex.encodeHexString(assetName.getBytes(StandardCharsets.UTF_8));
		}
	}

	private Predicate<? super TransactionInputs> isCharlyInput(List<TransactionInputs> offerFundings) {
		return of -> offerFundings.stream().anyMatch(
				check -> (check.getPolicyId() + "." + check.getAssetName()).equals(charlyToken)
						&& check.getTxhash().equals(of.getTxhash())
						&& check.getTxix() == of.getTxix());
	}

	private Predicate<? super TransactionInputs> isJackpotInput(List<TransactionInputs> offerFundings) {
		return of -> offerFundings.stream().anyMatch(
				check -> (check.getPolicyId()).equals(jackpotPolicy)
						&& check.getTxhash().equals(of.getTxhash())
						&& check.getTxix() == of.getTxix());
	}

}
