package de.peterspace.nftdropper.component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import de.peterspace.nftdropper.cardano.exceptions.UnexpectedTokensException;
import de.peterspace.nftdropper.cardano.exceptions.UnprocessedTransactionsException;
import de.peterspace.nftdropper.model.Address;
import de.peterspace.nftdropper.model.Policy;
import de.peterspace.nftdropper.model.TokenData;
import de.peterspace.nftdropper.model.TransactionInputs;
import de.peterspace.nftdropper.model.TransactionOutputs;
import lombok.RequiredArgsConstructor;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class NftMinter {

	@Value("${token.dir}")
	private String tokenDir;

	@Value("${seller.address}")
	private String sellerAddress;

	@Value("${token.price}")
	private long tokenPrice;

	@Value("${token.maxAmount}")
	private long tokenMaxAmount;

	private final CardanoCli cardanoCli;
	private final CardanoDbSyncClient cardanoDbSyncClient;
	private final NftSupplier nftSupplier;
	private final IpfsClient ipfsClient;

	private final Set<Long> blacklist = new HashSet<>();

	private Address paymentAddress;
	private Policy policy;

	@PostConstruct
	public void init() throws Exception {
		paymentAddress = cardanoCli.createPaymentAddress();
		policy = cardanoCli.createPolicy(paymentAddress.getVkey(), 365);
	}

	public String getPaymentAddress() {
		return paymentAddress.getAddress();
	}

	@Scheduled(cron = "*/10 * * * * *")
	public void processOffers() throws Exception {

		List<TransactionInputs> offerFundings = cardanoDbSyncClient.getOfferFundings(getPaymentAddress());
		Map<Long, List<TransactionInputs>> transactionInputGroups = offerFundings.stream().collect(Collectors.groupingBy(of -> of.getStakeAddressId(), LinkedHashMap::new, Collectors.toList()));

		List<List<TransactionInputs>> validTransactionInputGroups = transactionInputGroups
				.values()
				.stream()
				.filter(g -> !blacklist.contains(g.get(0).getStakeAddressId()))
				.filter(g -> g.stream().mapToLong(e -> e.getValue()).sum() >= (tokenPrice * 1_000_000))
				.collect(Collectors.toList());

		for (List<TransactionInputs> validTransactionInputGroup : validTransactionInputGroups) {
			if (nftSupplier.tokensLeft() > 0) {
				sell(validTransactionInputGroup);
			} else {
				refund(validTransactionInputGroup);
			}
		}

	}

	private void sell(List<TransactionInputs> transactionInputs) throws DecoderException, Exception, IOException {
		// determine amount of tokens
		String buyerAddress = transactionInputs.get(0).getSourceAddress();
		long funds = transactionInputs.stream().mapToLong(e -> e.getValue()).sum();
		int amount = (int) Math.min(Math.min(funds / (tokenPrice * 1_000_000), tokenMaxAmount), nftSupplier.tokensLeft());

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

		// build metadata
		JSONObject policyMetadata = new JSONObject();
		for (TokenData tokenData : tokens) {
			JSONObject metaData = tokenData.getMetaData();
			try (InputStream newInputStream = Files.newInputStream(Paths.get(tokenDir, tokenData.getFilename()))) {
				metaData.put("image", "ipfs://" + ipfsClient.addFile(newInputStream));
			}
			policyMetadata.put(tokenData.assetName(), metaData);
		}
		JSONObject metaData = new JSONObject().put("721", new JSONObject().put(policy.getPolicyId(), policyMetadata));

		try {
			String txId = cardanoCli.mint(transactionInputs, transactionOutputs, metaData, sellerAddress, paymentAddress, policy);
			log.info("Successfully sold, txid: {}", txId);
			nftSupplier.markTokenSold(tokens);
		} catch (UnexpectedTokensException e) {
			log.error("User {} sent tokens, blacklisting: {}", buyerAddress, e.getMessage());
			blacklist.add(transactionInputs.get(0).getStakeAddressId());
		} catch (UnprocessedTransactionsException e) {
			log.error("User {} has unprocessed transactions", buyerAddress, e.getMessage());
		}
	}

	private void refund(List<TransactionInputs> transactionInputs) throws Exception {
		// determine amount of tokens
		String buyerAddress = transactionInputs.get(0).getSourceAddress();

		TransactionOutputs transactionOutputs = new TransactionOutputs();

		try {
			String txId = cardanoCli.mint(transactionInputs, transactionOutputs, null, buyerAddress, paymentAddress, policy);
			log.info("Successfully refunded, txid: {}", txId);
		} catch (UnexpectedTokensException e) {
			log.error("User {} sent tokens, blacklisting: {}", buyerAddress, e.getMessage());
			blacklist.add(transactionInputs.get(0).getStakeAddressId());
		} catch (UnprocessedTransactionsException e) {
			log.error("User {} has unprocessed transactions", buyerAddress, e.getMessage());
		}
	}

}
