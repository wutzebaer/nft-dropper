package de.peterspace.nftdropper.component;

import static java.util.Map.entry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
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

import org.apache.commons.codec.DecoderException;
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

import de.peterspace.cardano.javalib.CardanoUtils;
import de.peterspace.cardanodbsyncapi.client.model.Utxo;
import de.peterspace.nftdropper.cardano.CardanoCli;
import de.peterspace.nftdropper.cardano.CardanoDbSyncClient;
import de.peterspace.nftdropper.model.Address;
import de.peterspace.nftdropper.model.CharlyJackpotCounter;
import de.peterspace.nftdropper.model.TransactionOutputs;
import de.peterspace.nftdropper.repository.CharlyJackpotCounterRepository;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class CharlySeller {

	@Value("${charly.jackpot.start}")
	private String jackpotStartString;
	private Instant jackpotStartTimestamp;

	@lombok.Value
	public static class JackpotCoin {
		String assetName;
		Long charlyTokens;
	}

	private Map<Integer, JackpotCoin> jackpot = Map.ofEntries(
			entry(1900, new JackpotCoin("CopperJackpotCoin028", 20000000l)),
			entry(2000, new JackpotCoin("CopperJackpotCoin029", 20000000l)),
			entry(2100, new JackpotCoin("CopperJackpotCoin033", 20000000l)),
			entry(2200, new JackpotCoin("SilverJackpotCoin022", 40000000l)),
			entry(2201, new JackpotCoin("SilverJackpotCoin006", 0l)),
			entry(2300, new JackpotCoin("CHARLYSGOLDENRUSH0004", 0l)),
			entry(2400, new JackpotCoin("CHARLYSGOLDENRUSH0005", 0l)),
			entry(2500, new JackpotCoin("CHARLYSGOLDENRUSH0006", 0l)));

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
	private final HunterService hunterService;

	private CharlyJackpotCounter charlyJackpotCounter;

	@Getter
	private List<CharlyTier> charlyTiers = new ArrayList<>();

	@Getter
	private Address paymentAddress;

	private final Cache<String, Boolean> blacklist = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build();
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

		jackpotStartTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mmz").parse(jackpotStartString + "UTC").toInstant();
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
		List<Utxo> offerFundings = cardanoDbSyncClient.getUtxos(paymentAddress.getAddress());
		List<Utxo> utxosWithCharlyTokens = getCharlyInputs(offerFundings);
		return countCharlyFunds(utxosWithCharlyTokens);
	}

	@Scheduled(cron = "0 * * * * *")
	public void processOffers() throws Exception {

		if (StringUtils.isBlank(charlyToken)) {
			return;
		}

		List<Utxo> offerFundings = new ArrayList<>(cardanoDbSyncClient.getUtxos(paymentAddress.getAddress()));
		List<Utxo> charlyUtxos = getCharlyInputs(offerFundings);
		Map<String, List<Utxo>> buyerUtxosGroupedByStakingAddress = getPaymentInputs(offerFundings);

		// use utxos equally
		Collections.shuffle(offerFundings);
		Collections.shuffle(charlyUtxos);

		final long minFunds = tokenPrice * 1_000_000;

		for (List<Utxo> buyerUtxos : buyerUtxosGroupedByStakingAddress.values()) {

			try {
				final String buyerAddress = cardanoDbSyncClient.getReturnAddress(buyerUtxos.get(0).getSourceAddress());
				final String stakeAddress = cardanoDbSyncClient.getStakeAddress(buyerAddress);

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

				List<Utxo> allUsedInputs = new ArrayList<>();
				allUsedInputs.addAll(buyerUtxos);

				TransactionOutputs transactionOutputs = new TransactionOutputs();

				// check if JACKPOT input is needed
				if (Instant.now().isAfter(jackpotStartTimestamp)) {
					for (int i = 0; i < amount; i++) {
						Integer currentTxNum = charlyJackpotCounter.getCount() + 1;
						log.info("Jackpot num: " + currentTxNum);
						JackpotCoin jackpotCoin = jackpot.get(currentTxNum);
						if (jackpotCoin != null) {
							log.info("HIT JACKPOT: " + jackpotCoin);
							// use inputs
							List<Utxo> jackpotInputs = getJackpotInputs(offerFundings);
							allUsedInputs.addAll(jackpotInputs);

							// send nft and jackpot charlies
							transactionOutputs.add(buyerAddress + "#jackpot", formatCurrency(charlyTokenPolicyId, charlyTokenAssetName), jackpotCoin.getCharlyTokens());
							transactionOutputs.add(buyerAddress + "#jackpot", formatCurrency(jackpotPolicy, Hex.encodeHexString(jackpotCoin.getAssetName().getBytes())), 1);

							// increae amount to gaster
							totalAmount += jackpotCoin.getCharlyTokens();

							// return other nfts
							jackpotInputs.removeIf(in -> Objects.equals(in.getMaName(), Hex.encodeHexString(jackpotCoin.getAssetName().getBytes())));
							jackpotInputs.removeIf(in -> StringUtils.isBlank(in.getMaPolicyId()));
							for (Utxo jackpotCoinInput : jackpotInputs) {
								transactionOutputs.add(paymentAddress.getAddress() + "#jackpot", formatCurrency(jackpotCoinInput.getMaPolicyId(), jackpotCoinInput.getMaName()), jackpotCoinInput.getValue());
								blacklistCharly.put(jackpotCoinInput.getTxHash() + "#" + jackpotCoinInput.getTxIndex(), true);
							}
						}
						charlyJackpotCounter.setCount(currentTxNum);
						charlyJackpotCounterRepository.save(charlyJackpotCounter);
					}
				} else {
					charlyJackpotCounter.setCount(0);
					charlyJackpotCounterRepository.save(charlyJackpotCounter);
				}

				// double output
				transactionOutputs.add(buyerAddress + "#double", formatCurrency(charlyTokenPolicyId, charlyTokenAssetName), totalAmount);
				totalAmount *= 2;

				// gather charlyToken inputs
				List<Utxo> reservedCharlyUtxos = new ArrayList<>();
				while (countCharlyFunds(reservedCharlyUtxos) < totalAmount && !charlyUtxos.isEmpty()) {
					Utxo reservedUtxo = charlyUtxos.get(0);
					List<Utxo> reservedUtxos = charlyUtxos.stream().filter(utxo -> utxo.getTxHash().equals(reservedUtxo.getTxHash()) && utxo.getTxIndex() == reservedUtxo.getTxIndex()).collect(Collectors.toList());
					charlyUtxos.removeAll(reservedUtxos);
					reservedCharlyUtxos.addAll(reservedUtxos);
				}
				long gatheredCharlies = countCharlyFunds(reservedCharlyUtxos) + countCharlyFunds(buyerUtxos);
				if (gatheredCharlies < totalAmount) {
					log.info("Not enough charly left, please start next tier, blacklisting utxos {}", CardanoUtils.extractStakePart(buyerUtxos.get(0).getSourceAddress()));
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
				List<Utxo> buyersSentTokens = buyerUtxos.stream().filter(e -> !StringUtils.isBlank(e.getMaPolicyId())).collect(Collectors.toList());
				if (!buyersSentTokens.isEmpty()) {
					for (Utxo buyerSentToken : buyersSentTokens) {
						transactionOutputs.add(buyerAddress, formatCurrency(buyerSentToken.getMaPolicyId(), buyerSentToken.getMaName()), buyerSentToken.getValue());
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

				for (Utxo usedInput : allUsedInputs) {
					blacklistCharly.put(usedInput.getTxHash() + "#" + usedInput.getTxIndex(), true);
				}

				hunterService.addMint(stakeAddress, totalAmount);

				log.info("Successfully sold {} , txid: {}", randomAmounts, txId);

			} catch (Exception e) {
				log.error("Utxo failed to process", e);
			} finally {
				blacklist.put(CardanoUtils.extractStakePart(buyerUtxos.get(0).getSourceAddress()), true);
			}

		}

	}

	private long calculateAvailableFunds(List<Utxo> transactionInputs) {
		return transactionInputs.stream().filter(e -> StringUtils.isBlank(e.getMaPolicyId())).mapToLong(e -> e.getValue()).sum();
	}

	private long calculateLockedFunds(List<Utxo> g) throws Exception {

		if (g.stream().filter(s -> !StringUtils.isBlank(s.getMaPolicyId())).findAny().isEmpty()) {
			return 0;
		}

		String addressValue = g.get(0).getSourceAddress() + " " + g.stream()
				.filter(s -> !StringUtils.isBlank(s.getMaPolicyId()))
				.map(s -> (s.getValue() + " " + formatCurrency(s.getMaPolicyId(), s.getMaName())).trim())
				.collect(Collectors.joining("+"));

		return cardanoCli.calculateMinUtxo(addressValue);
	}

	private long countCharlyFunds(List<Utxo> g) {
		return g.stream()
				.filter(of -> (of.getMaPolicyId() + "." + of.getMaName()).equals(charlyToken))
				.mapToLong(e -> e.getValue()).sum();
	}

	private long countCharlyInputs(List<Utxo> g) {
		return g.stream()
				.filter(of -> (of.getMaPolicyId() + "." + of.getMaName()).equals(charlyToken))
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

	private List<Utxo> getCharlyInputs(List<Utxo> offerFundings) {
		return offerFundings
				.stream()
				.filter(of -> blacklistCharly.getIfPresent(of.getTxHash() + "#" + of.getTxIndex()) == null)
				.filter(isCharlyInput(offerFundings))
				.collect(Collectors.toList());
	}

	private List<Utxo> getJackpotInputs(List<Utxo> offerFundings) {
		return offerFundings
				.stream()
				.filter(of -> blacklistCharly.getIfPresent(of.getTxHash() + "#" + of.getTxIndex()) == null)
				.filter(isJackpotInput(offerFundings))
				.collect(Collectors.toList());
	}

	private Map<String, List<Utxo>> getPaymentInputs(List<Utxo> offerFundings) {
		return offerFundings
				.stream()
				.filter(of -> blacklist.getIfPresent(CardanoUtils.extractStakePart(of.getSourceAddress())) == null)
				.filter(isCharlyInput(offerFundings).negate())
				.filter(isJackpotInput(offerFundings).negate())
				.filter(u -> !Objects.equals(u.getSourceAddress(), paymentAddress.getAddress()))
				.collect(Collectors.groupingBy(of -> CardanoUtils.extractStakePart(of.getSourceAddress()), LinkedHashMap::new, Collectors.toList()));
	}

	private String formatCurrency(String policyId, String assetNameHex) {
		if (StringUtils.isBlank(assetNameHex)) {
			return policyId;
		} else {
			return policyId + "." + assetNameHex;
		}
	}

	private Predicate<? super Utxo> isCharlyInput(List<Utxo> offerFundings) {
		return of -> offerFundings.stream().anyMatch(
				check -> Objects.equals(check.getMaPolicyId() + "." + check.getMaName(), charlyToken)
						&& check.getTxHash().equals(of.getTxHash())
						&& check.getTxIndex() == of.getTxIndex());
	}

	private Predicate<? super Utxo> isJackpotInput(List<Utxo> offerFundings) {
		return of -> offerFundings.stream().anyMatch(
				check -> Objects.equals(check.getMaPolicyId(), jackpotPolicy)
						&& check.getTxHash().equals(of.getTxHash())
						&& check.getTxIndex() == of.getTxIndex());
	}

}
