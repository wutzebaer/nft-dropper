package de.peterspace.nftdropper.cardano;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import de.peterspace.cardano.javalib.CardanoUtils;
import de.peterspace.cardanodbsyncapi.client.model.Utxo;
import de.peterspace.nftdropper.model.Address;
import de.peterspace.nftdropper.model.Policy;
import de.peterspace.nftdropper.model.Transaction;
import de.peterspace.nftdropper.model.TransactionOutputs;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Validated
@RequiredArgsConstructor
@Slf4j
public class CardanoCli {

	private static final Long oneAda = 1_000_000l;
	private static final Pattern lovelacePattern = Pattern.compile("Lovelace (\\d+)");
	private static final String PAYMENT_VKEY_FILENAME = "payment.vkey";
	private static final String PAYMENT_SKEY_FILENAME = "payment.skey";
	private static final String PAYMENT_ADDR_FILENAME = "payment.addr";
	private static final String POLICY_ID_FILENAME = "policy.id";
	private static final String POLICY_SCRIPT_FILENAME = "policy.script";
	private static final String POLICY_SKEY_FILENAME = "policy.skey";
	private static final String POLICY_VKEY_FILENAME = "policy.vkey";
	private static final String POLICY_ADDR_FILENAME = "policy.addr";
	private static final Pattern missingLovelacePattern = Pattern.compile("Lovelace \\(-(\\d+)\\)");

	@Value("${network}")
	private String network;

	@Value("${working.dir.external}")
	private String workingDirExternal;

	private final CardanoNode cardanoNode;
	private final FileUtil fileUtil;
	private final CardanoCliDockerBridge cardanoCliDockerBridge;

	private String protocolJson;
	private String dummyAddress;

	@PostConstruct
	public void init() throws Exception {

		ArrayList<String> cmd = new ArrayList<String>();
		cmd.add("query");
		cmd.add("protocol-parameters");
		cmd.add("--out-file");
		cmd.add("protocol.json");
		protocolJson = cardanoCliDockerBridge.requestCardanoCli(null, cmd.toArray(new String[0]), "protocol.json")[1];

		if (network.equals("preview")) {
			dummyAddress = "addr_test1qp8cprhse9pnnv7f4l3n6pj0afq2hjm6f7r2205dz0583ed6zj0zugmep9lxtuxq8unn85csx9g70ugq6dklmvq6pv3qa0n8cl";
		} else if (network.equals("mainnet")) {
			dummyAddress = "addr1q9h7988xmmpz2y50rg2n9fw6jd5rq95t8q84k4q6ne403nxahea9slntm5n8f06nlsynyf4m6sa0qd05agra0qgk09nq96rqh9";
		} else {
			throw new RuntimeException("Network must be preview or mainnet");
		}

	}

	private HashMap<String, Long> calculateMinUtxoCache = new HashMap<>();

	public long calculateMinUtxo(String addressValue) throws Exception {
		String normalizedAddressValue = normalizeAddressValue(addressValue + "+" + oneAda);
		Long cachedValue = calculateMinUtxoCache.get(normalizedAddressValue);
		if (cachedValue != null) {
			return cachedValue;
		}

		ArrayList<String> cmd = new ArrayList<String>();

		cmd.add("transaction");
		cmd.add("calculate-min-required-utxo");

		cmd.add("--protocol-params-file");
		cmd.add("protocol.json");

		if ("Babbage".equals(cardanoNode.getEra())) {
			cmd.add("--babbage-era");
		}

		cmd.add("--tx-out");
		cmd.add(normalizedAddressValue);

		String feeString = cardanoCliDockerBridge.requestCardanoCliNomagic(Map.of("protocol.json", protocolJson), cmd.toArray(new String[0]))[0];
		long fee = Long.valueOf(feeString.split(" ")[1]);

		calculateMinUtxoCache.put(normalizedAddressValue, fee);

		return fee;
	}

	private String normalizeAddressValue(String addressValue) {
		String[] bits = addressValue.split("\\+");
		bits[0] = dummyAddress;
		for (int i = 1; i < bits.length; i++) {
			String[] valueBits = bits[i].split(" ");
			valueBits[0] = "1000000";
			bits[i] = StringUtils.join(valueBits, " ");
		}
		return StringUtils.join(bits, "+");
	}

	public Address createPaymentAddress() throws Exception {
		String skeyFilename = prefixFilename(PAYMENT_SKEY_FILENAME);
		String vkeyFilename = prefixFilename(PAYMENT_VKEY_FILENAME);
		String addressFilename = prefixFilename(PAYMENT_ADDR_FILENAME);
		Address createAddress = createAddress(skeyFilename, vkeyFilename, addressFilename);
		return createAddress;
	}

	public Address createPolicyAddress() throws Exception {
		String skeyFilename = prefixFilename(POLICY_SKEY_FILENAME);
		String vkeyFilename = prefixFilename(POLICY_VKEY_FILENAME);
		String addressFilename = prefixFilename(POLICY_ADDR_FILENAME);
		return createAddress(skeyFilename, vkeyFilename, addressFilename);
	}

	public Address createDisposableAddress() throws Exception {
		String skeyFilename = prefixFilename(UUID.randomUUID().toString() + "." + PAYMENT_SKEY_FILENAME);
		String vkeyFilename = prefixFilename(UUID.randomUUID().toString() + "." + PAYMENT_VKEY_FILENAME);
		String addressFilename = prefixFilename(UUID.randomUUID().toString() + "." + PAYMENT_ADDR_FILENAME);
		Address createAddress = createAddress(skeyFilename, vkeyFilename, addressFilename);
		fileUtil.removeFile(skeyFilename);
		fileUtil.removeFile(vkeyFilename);
		fileUtil.removeFile(addressFilename);
		return createAddress;
	}

	private Address createAddress(String skeyFilename, String vkeyFilename, String addressFilename) throws Exception {
		if (fileUtil.exists(skeyFilename)) {
			return new Address(
					fileUtil.readFile(addressFilename),
					fileUtil.readFile(skeyFilename),
					fileUtil.readFile(vkeyFilename),
					0);
		}

		ArrayList<String> cmd = new ArrayList<String>();
		cmd.add("address");
		cmd.add("key-gen");
		cmd.add("--signing-key-file");
		cmd.add(skeyFilename);
		cmd.add("--verification-key-file");
		cmd.add(vkeyFilename);
		String[] keyGenResponse = cardanoCliDockerBridge.requestCardanoCliNomagic(null, cmd.toArray(new String[0]), skeyFilename, vkeyFilename);

		cmd = new ArrayList<String>();
		cmd.add("address");
		cmd.add("build");
		cmd.add("--payment-verification-key-file");
		cmd.add(vkeyFilename);
		String addressLiteral = cardanoCliDockerBridge.requestCardanoCli(Map.of(vkeyFilename, keyGenResponse[2]), cmd.toArray(new String[0]))[0];

		Address address = new Address(addressLiteral, keyGenResponse[1], keyGenResponse[2], 0);

		fileUtil.writeFile(addressFilename, address.getAddress());
		fileUtil.writeFile(skeyFilename, address.getSkey());
		fileUtil.writeFile(vkeyFilename, address.getVkey());

		return address;
	}

	public Policy createPolicy(int days) throws Exception {

		createPolicyAddress();

		String policyFilename = prefixFilename(POLICY_SCRIPT_FILENAME);
		String policyIdFilename = prefixFilename(POLICY_ID_FILENAME);

		String policyString;
		if (!fileUtil.exists(policyFilename)) {

			long secondsToLive = 60 * 60 * 24 * days;
			long dueSlot = CardanoUtils.currentSlot() + secondsToLive;

			String vkeyFilename = prefixFilename(POLICY_VKEY_FILENAME);

			// address hash
			ArrayList<String> cmd1 = new ArrayList<String>();
			cmd1.add("address");
			cmd1.add("key-hash");
			cmd1.add("--payment-verification-key-file");
			cmd1.add(vkeyFilename);
			String keyHash = cardanoCliDockerBridge.requestCardanoCliNomagic(Map.of(vkeyFilename, fileUtil.readFile(vkeyFilename)), cmd1.toArray(new String[0]))[0];

			// @formatter:off
	        JSONObject script = new JSONObject()
	                .put("type", "all")
	                .put("scripts",
	                        new JSONArray()
	                                .put(new JSONObject()
	                                        .put("slot", dueSlot)
	                                        .put("type", "before"))
	                                .put(new JSONObject()
	                                        .put("keyHash", keyHash)
	                                        .put("type", "sig")));
        	// @formatter:on

			policyString = script.toString(3);
			fileUtil.writeFile(policyFilename, policyString);
		} else {
			policyString = fileUtil.readFile(policyFilename);
		}

		ArrayList<String> cmd2 = new ArrayList<String>();
		cmd2.add("transaction");
		cmd2.add("policyid");
		cmd2.add("--script-file");
		cmd2.add(policyFilename);
		String policyId = cardanoCliDockerBridge.requestCardanoCliNomagic(Map.of(policyFilename, fileUtil.readFile(policyFilename)), cmd2.toArray(new String[0]))[0];
		fileUtil.writeFile(policyIdFilename, policyId);

		return new Policy(policyString, policyId);
	}

	public String mint(List<Utxo> transactionInputs, TransactionOutputs transactionOutputs, JSONObject metaData, Address paymentAddress, Policy policy, String changeAddress) throws Exception {
		Transaction tx = buildTransaction(transactionInputs, transactionOutputs, metaData != null ? metaData.toString(3) : null, policy, changeAddress);

		if (policy != null) {
			signTransaction(tx, paymentAddress, createPolicyAddress());
		} else {
			signTransaction(tx, paymentAddress);
		}
		String txId = getTxId(tx);
		submitTransaction(tx);
		return txId;
	}

	public Transaction buildTransaction(List<Utxo> transactionInputs, TransactionOutputs transactionOutputs, String metaData, Policy policy, String changeAddress) throws Exception {

		Map<String, String> inputFiles = new HashMap<>();
		ArrayList<String> cmd = new ArrayList<String>();

		cmd.add("conway");
		cmd.add("transaction");
		cmd.add("build");

		cmd.add("--change-address");
		cmd.add(changeAddress);

		cmd.add("--witness-override");
		cmd.add(policy == null ? "1" : "2");

		for (Utxo utxo : transactionInputs) {
			cmd.add("--tx-in");
			cmd.add(utxo.getTxHash() + "#" + utxo.getTxIndex());
		}

		for (String a : transactionOutputs.toCliFormat()) {
			cmd.add("--tx-out");
			cmd.add(a);
		}

		List<String> mints = new ArrayList<String>();

		Set<String> outputAssets = transactionOutputs.getOutputs().values()
				.stream()
				.flatMap(a -> a.keySet().stream())
				.filter(e -> !StringUtils.isBlank(e))
				.collect(Collectors.toSet());
		Set<String> inputAssets = transactionInputs
				.stream()
				.filter(e -> e.getMaPolicyId() != null)
				.map(e -> formatCurrency(e.getMaPolicyId(), e.getMaName()))
				.collect(Collectors.toSet());
		Set<String> allAssets = new HashSet<>();
		allAssets.addAll(outputAssets);
		allAssets.addAll(inputAssets);
		for (String assetEntry : allAssets) {
			long inputAmount = transactionInputs.stream().filter(i -> Objects.equals(formatCurrency(i.getMaPolicyId(), i.getMaName()), assetEntry)).mapToLong(i -> i.getValue()).sum();
			long outputAmount = transactionOutputs.getOutputs().values().stream().flatMap(a -> a.entrySet().stream()).filter(e -> Objects.equals(e.getKey(), assetEntry)).mapToLong(e -> e.getValue()).sum();
			long needed = outputAmount - inputAmount;
			if (needed != 0) {
				mints.add(String.format("%d %s", needed, assetEntry));
			}
		}

		if (mints.size() > 0) {
			String policyScriptFilename = filename("script");
			cmd.add("--mint");
			cmd.add(StringUtils.join(mints, "+"));
			cmd.add("--minting-script-file");
			cmd.add(policyScriptFilename);
			inputFiles.put(policyScriptFilename, policy.getPolicy());
		}

		if (metaData != null) {
			String metadataFilename = filename("metadata");
			cmd.add("--json-metadata-no-schema");
			cmd.add("--metadata-json-file");
			cmd.add(metadataFilename);
			inputFiles.put(metadataFilename, metaData);
		}

		String txUnsignedFilename = filename("unsigned");
		cmd.add("--out-file");
		cmd.add(txUnsignedFilename);

		if (policy != null) {
			long maxSlot = new JSONObject(policy.getPolicy()).getJSONArray("scripts").getJSONObject(0).getLong("slot");
			cmd.add("--invalid-hereafter");
			cmd.add("" + maxSlot);
		}

		String[] txResponse;
		try {
			txResponse = cardanoCliDockerBridge.requestCardanoCli(inputFiles, cmd.toArray(new String[0]), txUnsignedFilename);
		} catch (Exception e) {
			log.warn("first mint attempt failed: {}", e.getMessage());
			String message = StringUtils.trimToEmpty(e.getMessage());
			if (message.contains("(change output)")) {
				Matcher matcher = lovelacePattern.matcher(e.getMessage());
				matcher.find();
				Long missingFunds = Long.valueOf(matcher.group(1));
				transactionOutputs.add(transactionOutputs.getOutputs().keySet().iterator().next(), "", missingFunds);
				return buildTransaction(transactionInputs, transactionOutputs, metaData, policy, changeAddress);
			} else if (message.contains("The net balance of the transaction is negative")) {
				Matcher matcher = missingLovelacePattern.matcher(e.getMessage());
				matcher.find();
				long missingFunds = Long.parseLong(matcher.group(1));
				throw new MissingLovelaceException(missingFunds, e.getMessage(), e);
			} else {
				throw e;
			}
		}

		Transaction transaction = new Transaction();
		String[] feeStringChunks = txResponse[0].split(" ");
		transaction.setFee(Long.valueOf(feeStringChunks[feeStringChunks.length - 1]));
		transaction.setInputs(transactionInputs.toString());
		transaction.setOutputs(transactionOutputs.toString());
		if (metaData != null) {
			transaction.setMetaDataJson(metaData);
		}
		transaction.setRawData(txResponse[1]);

		String txId = getTxId(transaction);
		transaction.setTxId(txId);

		return transaction;
	}

	private void signTransaction(Transaction mintTransaction, Address... addresses) throws Exception {

		Map<String, String> inputFiles = new HashMap<>();

		List<String> skeyFilenames = new ArrayList<String>();
		for (Address address : addresses) {
			String skeyFilename = filename("skey");
			skeyFilenames.add(skeyFilename);
			inputFiles.put(skeyFilename, address.getSkey());
		}

		String rawFilename = filename("raw");
		inputFiles.put(rawFilename, mintTransaction.getRawData());

		String signedFilename = filename("signed");

		ArrayList<String> cmd = new ArrayList<String>();
		cmd.add("transaction");
		cmd.add("sign");

		for (String skeyFilename : skeyFilenames) {
			cmd.add("--signing-key-file");
			cmd.add(skeyFilename);
		}

		cmd.add("--tx-body-file");
		cmd.add(rawFilename);

		cmd.add("--out-file");
		cmd.add(signedFilename);

		String[] output = cardanoCliDockerBridge.requestCardanoCliNomagic(inputFiles, cmd.toArray(new String[0]), signedFilename);

		mintTransaction.setSignedData(output[1]);

		String cborHex = new JSONObject(mintTransaction.getSignedData()).getString("cborHex");
		mintTransaction.setTxSize((long) (cborHex.length() / 2));
	}

	public void submitTransaction(Transaction mintTransaction) throws Exception {
		String filename = filename("signed");
		try {

			ArrayList<String> cmd = new ArrayList<String>();
			cmd.add("transaction");
			cmd.add("submit");

			cmd.add("--tx-file");
			cmd.add(filename);

			cardanoCliDockerBridge.requestCardanoCli(Map.of(filename, mintTransaction.getSignedData()), cmd.toArray(new String[0]));

		} catch (Exception e) {
			String message = StringUtils.defaultIfEmpty(e.getMessage(), "");
			if (message.contains("BadInputsUTxO")) {
				throw new Exception("You have unprocessed transactions, please wait a minute.");
			} else if (message.contains("OutsideValidityIntervalUTxO")) {
				throw new Exception("You policy has expired. Confirm to generate a new one.");
			} else {
				throw e;
			}
		}
	}

	private String getTxId(Transaction mintTransaction) throws Exception {
		String filename = filename("raw");
		ArrayList<String> cmd = new ArrayList<String>();
		cmd.add("transaction");
		cmd.add("txid");
		cmd.add("--tx-body-file");
		cmd.add(filename);
		String txId = cardanoCliDockerBridge.requestCardanoCliNomagic(Map.of(filename, mintTransaction.getRawData()), cmd.toArray(new String[0]))[0];
		return txId;
	}

	private String formatCurrency(String policyId, String assetNameHex) {
		if (StringUtils.isBlank(assetNameHex)) {
			return policyId;
		} else {
			return policyId + "." + assetNameHex;
		}
	}

	private String filename(String ext) {
		return UUID.randomUUID().toString() + "." + ext;
	}

	private String prefixFilename(String filename) {
		return network + "." + filename;
	}

}
