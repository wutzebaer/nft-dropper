package de.peterspace.nftdropper.component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.codec.DecoderException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import de.peterspace.nftdropper.cardano.CardanoCli;
import de.peterspace.nftdropper.cardano.CardanoDbSyncClient;
import de.peterspace.nftdropper.cardano.CardanoNode;
import de.peterspace.nftdropper.cardano.exceptions.UnexpectedTokensException;
import de.peterspace.nftdropper.cardano.exceptions.UnprocessedTransactionsException;
import de.peterspace.nftdropper.model.Address;
import de.peterspace.nftdropper.model.Policy;
import de.peterspace.nftdropper.model.TokenData;
import de.peterspace.nftdropper.model.TransactionInputs;
import de.peterspace.nftdropper.model.TransactionOutputs;
import de.peterspace.nftdropper.repository.AddressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class NftMinter {

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

	private final CardanoCli cardanoCli;
	private final CardanoNode cardanoNode;
	private final CardanoDbSyncClient cardanoDbSyncClient;
	private final NftSupplier nftSupplier;
	private final IpfsClient ipfsClient;
	private final AddressRepository addressRepository;

	private final Set<TransactionInputs> blacklist = new HashSet<>();

	private Address paymentAddress;
	private Policy policy;

	@PostConstruct
	public void init() throws Exception {
		paymentAddress = cardanoCli.createPaymentAddress();
		policy = cardanoCli.createPolicy(365);
		log.info("Seller Address: {}", sellerAddress);
		log.info("Token Price: {}", tokenPrice);
		log.info("Token Max Amount: {}", tokenMaxAmount);
		log.info("Policy Id: {}", policy.getPolicyId());
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

		List<List<TransactionInputs>> validTransactionInputGroups = transactionInputGroups
				.values()
				.stream()
				.filter(g -> nftSupplier.tokensLeft() == 0 || g.stream().mapToLong(e -> e.getValue()).sum() >= (tokenPrice * 1_000_000))
				.collect(Collectors.toList());

		for (List<TransactionInputs> validTransactionInputGroup : validTransactionInputGroups) {
			try {
				if (nftSupplier.tokensLeft() > 0 && (tokenMaxAmount - fundAddress.getTokensMinted()) > 0) {
					sell(fundAddress, validTransactionInputGroup);
				} else {
					refund(fundAddress, validTransactionInputGroup);
				}
				blacklist.addAll(validTransactionInputGroup);
			} catch (Exception e) {
				log.error(fundAddress.getAddress() + " failed", e);
			}
		}
	}

	private void sell(Address fundAddress, List<TransactionInputs> transactionInputs) throws DecoderException, Exception, IOException {
		// determine amount of tokens
		String buyerAddress = transactionInputs.get(0).getSourceAddress();
		long funds = transactionInputs.stream().mapToLong(e -> e.getValue()).sum();
		int amount = (int) Math.min(Math.min(funds / (tokenPrice * 1_000_000), tokenMaxAmount - fundAddress.getTokensMinted()), nftSupplier.tokensLeft());

		// select tokens
		List<TokenData> tokens = nftSupplier.getTokens(amount);
		log.info("selling {} tokens to {} : {}", amount, buyerAddress, tokens);

		TransactionOutputs transactionOutputs = new TransactionOutputs();

		// send tokens
		for (TokenData token : tokens) {
			transactionOutputs.add(buyerAddress, policy.getPolicyId() + "." + token.assetName(), 1);
		}

		// min output for tokens
		long minOutput = MinOutputCalculator.calculate(tokens.stream().map(f -> f.assetName()).collect(Collectors.toSet()), 1);
		transactionOutputs.add(buyerAddress, "", minOutput);

		// if user paid more than needed
		long change = funds - amount * (tokenPrice * 1_000_000);
		if (change > 0) {
			transactionOutputs.add(buyerAddress, "", change);
		}

		if (donate) {
			transactionOutputs.add(cardanoNode.getDonationAddress(), "", 1_000_000);
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

		try {
			String txId = cardanoCli.mint(transactionInputs, transactionOutputs, metaData, sellerAddress, fundAddress, policy);
			log.info("Successfully sold, txid: {}", txId);
			nftSupplier.markTokenSold(tokens);

			fundAddress.setTokensMinted(fundAddress.getTokensMinted() + amount);
			addressRepository.save(fundAddress);
			log.info("{} has {} tokens left", fundAddress.getAddress(), tokenMaxAmount - fundAddress.getTokensMinted());

		} catch (UnexpectedTokensException e) {
			log.error("User {} sent tokens, blacklisting: {}", buyerAddress, e.getMessage());
		} catch (UnprocessedTransactionsException e) {
			log.error("User {} has unprocessed transactions", buyerAddress, e.getMessage());
		}
	}

	private void refund(Address fundAddress, List<TransactionInputs> transactionInputs) throws Exception {
		// determine amount of tokens
		String buyerAddress = transactionInputs.get(0).getSourceAddress();

		TransactionOutputs transactionOutputs = new TransactionOutputs();

		try {
			String txId = cardanoCli.mint(transactionInputs, transactionOutputs, null, buyerAddress, fundAddress, policy);
			log.info("Successfully refunded, txid: {}", txId);
		} catch (UnexpectedTokensException e) {
			log.error("User {} sent tokens, blacklisting: {}", buyerAddress, e.getMessage());
		} catch (UnprocessedTransactionsException e) {
			log.error("User {} has unprocessed transactions", buyerAddress, e.getMessage());
		}
	}

}
