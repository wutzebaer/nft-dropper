package de.peterspace.nftdropper.cardano;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import de.peterspace.nftdropper.cardano.exceptions.OutputTooSmallUTxOException;
import de.peterspace.nftdropper.cardano.exceptions.PolicyExpiredException;
import de.peterspace.nftdropper.cardano.exceptions.UnexpectedTokensException;
import de.peterspace.nftdropper.cardano.exceptions.UnprocessedTransactionsException;
import de.peterspace.nftdropper.model.Address;
import de.peterspace.nftdropper.model.Policy;
import de.peterspace.nftdropper.model.TransactionInputs;
import de.peterspace.nftdropper.model.TransactionOutputs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Validated
@Slf4j
@RequiredArgsConstructor
public class CardanoCli {

	private static final String PAYMENT_VKEY_FILENAME = "payment.vkey";
	private static final String PAYMENT_SKEY_FILENAME = "payment.skey";
	private static final String PAYMENT_ADDR_FILENAME = "payment.addr";
	private static final String POLICY_ID_FILENAME = "policy.id";
	private static final String POLICY_SCRIPT_FILENAME = "policy.script";
	private static final String POLICY_SKEY_FILENAME = "policy.skey";
	private static final String POLICY_VKEY_FILENAME = "policy.vkey";
	private static final String POLICY_ADDR_FILENAME = "policy.addr";
	private static final String PROTOCOL_JSON_FILENAME = "protocol.json";
	private static final String TRANSACTION_UNSIGNED_FILENAME = "transaction.unsigned";
	private static final String TRANSACTION_METADATA_JSON_FILENAME = "transactionMetadata.json";
	private static final String TRANSACTION_SIGNED_FILENAME = "transaction.signed";

	@Value("${NETWORK}")
	private String network;

	@Value("${cardano-node.ipc-volume-name}")
	private String ipcVolumeName;

	@Value("${working.dir.external}")
	private String workingDirExternal;

	private final CardanoNode cardanoNode;
	private final FileUtil fileUtil;

	private String[] cardanoCliCmd;
	private String[] networkMagicArgs;

	private String prefixFilename(String filename) {
		return network + "." + filename;
	}

	@PostConstruct
	public void init() throws Exception {

		// @formatter:off
        cardanoCliCmd = new String[] {
                "docker", "run",
                "--rm",
                "--entrypoint", "cardano-cli",
                "-w", "/work",
                "-e", "CARDANO_NODE_SOCKET_PATH=/ipc/node.socket",
                "-v", ipcVolumeName + ":/ipc",
                "-v", workingDirExternal + ":/work",
                "inputoutput/cardano-node"
        };
        // @formatter:on
		this.networkMagicArgs = cardanoNode.getNetworkMagicArgs();

		ArrayList<String> cmd = new ArrayList<String>();
		cmd.addAll(List.of(cardanoCliCmd));
		cmd.add("query");
		cmd.add("protocol-parameters");
		cmd.add("--out-file");
		cmd.add(prefixFilename(PROTOCOL_JSON_FILENAME));
		cmd.addAll(List.of(networkMagicArgs));
		ProcessUtil.runCommand(cmd.toArray(new String[0]));

	}

	public long queryTip() throws Exception {
		ArrayList<String> cmd = new ArrayList<String>();
		cmd.addAll(List.of(cardanoCliCmd));
		cmd.add("query");
		cmd.add("tip");
		cmd.addAll(List.of(networkMagicArgs));
		String jsonString = ProcessUtil.runCommand(cmd.toArray(new String[0]));
		JSONObject jsonObject = new JSONObject(jsonString);
		return jsonObject.getLong("slot");
	}

	public Address createPaymentAddress() throws Exception {
		String skeyFilename = prefixFilename(PAYMENT_SKEY_FILENAME);
		String vkeyFilename = prefixFilename(PAYMENT_VKEY_FILENAME);
		String addressFilename = prefixFilename(PAYMENT_ADDR_FILENAME);
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

	public Address createPolicyAddress() throws Exception {
		String skeyFilename = prefixFilename(POLICY_SKEY_FILENAME);
		String vkeyFilename = prefixFilename(POLICY_VKEY_FILENAME);
		String addressFilename = prefixFilename(POLICY_ADDR_FILENAME);
		return createAddress(skeyFilename, vkeyFilename, addressFilename);
	}

	private Address createAddress(String skeyFilename, String vkeyFilename, String addressFilename) throws Exception {
		if (!fileUtil.exists(skeyFilename)) {
			ArrayList<String> cmd = new ArrayList<String>();
			cmd.addAll(List.of(cardanoCliCmd));
			cmd.add("address");
			cmd.add("key-gen");
			cmd.add("--verification-key-file");
			cmd.add(vkeyFilename);
			cmd.add("--signing-key-file");
			cmd.add(skeyFilename);
			ProcessUtil.runCommand(cmd.toArray(new String[0]));
		}

		ArrayList<String> cmd = new ArrayList<String>();
		cmd.addAll(List.of(cardanoCliCmd));
		cmd.add("address");
		cmd.add("build");
		cmd.add("--payment-verification-key-file");
		cmd.add(vkeyFilename);
		cmd.add("--out-file");
		cmd.add(addressFilename);
		cmd.addAll(List.of(networkMagicArgs));
		ProcessUtil.runCommand(cmd.toArray(new String[0]));

		String skey = fileUtil.readFile(skeyFilename);
		String vkey = fileUtil.readFile(vkeyFilename);
		String addressLiteral = fileUtil.readFile(addressFilename);

		Address address = new Address(addressLiteral, skey, vkey, 0, null);
		return address;
	}

	public Policy createPolicy(int days) throws Exception {

		createPolicyAddress();

		String policyFilename = prefixFilename(POLICY_SCRIPT_FILENAME);
		String policyIdFilename = prefixFilename(POLICY_ID_FILENAME);

		String policyString;
		if (!fileUtil.exists(policyFilename)) {

			long secondsToLive = 60 * 60 * 24 * days;
			long dueSlot = queryTip() + secondsToLive;

			String vkeyFilename = prefixFilename(POLICY_VKEY_FILENAME);

			// address hash
			ArrayList<String> cmd1 = new ArrayList<String>();
			cmd1.addAll(List.of(cardanoCliCmd));
			cmd1.add("address");
			cmd1.add("key-hash");
			cmd1.add("--payment-verification-key-file");
			cmd1.add(vkeyFilename);
			String keyHash = ProcessUtil.runCommand(cmd1.toArray(new String[0]));

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
		cmd2.addAll(List.of(cardanoCliCmd));
		cmd2.add("transaction");
		cmd2.add("policyid");
		cmd2.add("--script-file");
		cmd2.add(policyFilename);
		String policyId = ProcessUtil.runCommand(cmd2.toArray(new String[0]));
		fileUtil.writeFile(policyIdFilename, policyId);

		return new Policy(policyString, policyId);
	}

	public long calculateFee(List<TransactionInputs> transactionInputs, TransactionOutputs transactionOutputs, JSONObject metaData, Address paymentAddress, Policy policy) throws Exception {
		buildTransaction(transactionInputs, transactionOutputs, metaData, policy, 0);

		String filename = prefixFilename(TRANSACTION_UNSIGNED_FILENAME);

		ArrayList<String> cmd = new ArrayList<String>();
		cmd.addAll(List.of(cardanoCliCmd));

		cmd.add("transaction");
		cmd.add("calculate-min-fee");

		cmd.add("--tx-body-file");
		cmd.add(filename);

		cmd.add("--tx-in-count");
		long inCount = transactionInputs.stream().map(txin -> txin.getTxhash() + txin.getTxix()).distinct().count();
		cmd.add("" + inCount);

		cmd.add("--tx-out-count");
		long outCount = transactionOutputs.getOutputs().size();
		cmd.add("" + outCount);

		cmd.add("--witness-count");
		cmd.add("" + (policy != null ? 2 : 1));

		cmd.addAll(List.of(networkMagicArgs));

		cmd.add("--protocol-params-file");
		cmd.add(prefixFilename(PROTOCOL_JSON_FILENAME));

		String feeString = ProcessUtil.runCommand(cmd.toArray(new String[0]));
		long fee = Long.valueOf(feeString.split(" ")[0]);

		return fee;
	}

	public String mint(List<TransactionInputs> transactionInputs, TransactionOutputs transactionOutputs, JSONObject metaData, Address paymentAddress, Policy policy, long fees) throws Exception {
		try {

			buildTransaction(transactionInputs, transactionOutputs, metaData, policy, fees);
			signTransaction(paymentAddress, policy != null);
			String txId = getTransactionId();
			submitTransaction();
			return txId;

		} catch (Exception e) {
			if (e.getMessage().contains("BadInputsUTxO")) {
				throw new UnprocessedTransactionsException(e.getMessage());
			} else if (e.getMessage().contains("OutsideValidityIntervalUTxO")) {
				throw new PolicyExpiredException(e.getMessage());
			} else if (e.getMessage().contains("Non-Ada assets are unbalanced")) {
				throw new UnexpectedTokensException(e.getMessage());
			} else if (e.getMessage().contains("OutputTooSmallUTxO")) {
				throw new OutputTooSmallUTxOException(e.getMessage());
			} else {
				throw e;
			}
		}

	}

	private void submitTransaction() throws Exception {
		ArrayList<String> cmd = new ArrayList<String>();
		cmd.addAll(List.of(cardanoCliCmd));
		cmd.add("transaction");
		cmd.add("submit");

		cmd.add("--tx-file");
		cmd.add(prefixFilename(TRANSACTION_SIGNED_FILENAME));

		cmd.addAll(List.of(networkMagicArgs));

		ProcessUtil.runCommand(cmd.toArray(new String[0]));
	}

	private String getTransactionId() throws Exception {
		String txId;
		{
			ArrayList<String> cmd = new ArrayList<String>();
			cmd.addAll(List.of(cardanoCliCmd));
			cmd.add("transaction");
			cmd.add("txid");
			cmd.add("--tx-file");
			cmd.add(prefixFilename(TRANSACTION_SIGNED_FILENAME));
			txId = ProcessUtil.runCommand(cmd.toArray(new String[0]));
		}
		return txId;
	}

	private void buildTransaction(List<TransactionInputs> transactionInputs, TransactionOutputs transactionOutputs, JSONObject metaData, Policy policy, long fees) throws Exception {
		{

			ArrayList<String> cmd = new ArrayList<String>();
			cmd.addAll(List.of(cardanoCliCmd));

			cmd.add("transaction");
			cmd.add("build-raw");

			cmd.add("--fee");
			cmd.add("" + fees);

			for (TransactionInputs utxo : transactionInputs) {
				cmd.add("--tx-in");
				cmd.add(utxo.getTxhash() + "#" + utxo.getTxix());
			}

			for (String a : transactionOutputs.toCliFormat()) {
				cmd.add("--tx-out");
				cmd.add(a);
			}

			Set<String> outputAssets = transactionOutputs.getOutputs().values()
					.stream()
					.flatMap(a -> a.keySet().stream())
					.filter(e -> !StringUtils.isBlank(e))
					.collect(Collectors.toSet());
			List<String> mints = new ArrayList<String>();
			for (String assetEntry : outputAssets) {
				long inputAmount = transactionInputs.stream().filter(i -> Objects.equals(formatCurrency(i.getPolicyId(), i.getAssetName()), assetEntry)).mapToLong(i -> i.getValue()).sum();
				long outputAmount = transactionOutputs.getOutputs().values().stream().flatMap(a -> a.entrySet().stream()).filter(e -> Objects.equals(e.getKey(), assetEntry)).mapToLong(e -> e.getValue()).sum();
				long needed = outputAmount - inputAmount;
				if (needed > 0) {
					mints.add(String.format("%d %s", needed, assetEntry));
				}
			}
			if (mints.size() > 0) {
				cmd.add("--mint");
				cmd.add(StringUtils.join(mints, "+"));
				cmd.add("--minting-script-file");
				cmd.add(prefixFilename(POLICY_SCRIPT_FILENAME));
			}

			if (metaData != null) {
				fileUtil.writeFile(prefixFilename(TRANSACTION_METADATA_JSON_FILENAME), metaData.toString(3));
				cmd.add("--json-metadata-no-schema");
				cmd.add("--metadata-json-file");
				cmd.add(prefixFilename(TRANSACTION_METADATA_JSON_FILENAME));
			}

			cmd.add("--out-file");
			cmd.add(prefixFilename(TRANSACTION_UNSIGNED_FILENAME));

			if (policy != null) {
				long maxSlot = new JSONObject(policy.getPolicy()).getJSONArray("scripts").getJSONObject(0).getLong("slot");
				cmd.add("--invalid-hereafter");
				cmd.add("" + maxSlot);
			}

			ProcessUtil.runCommand(cmd.toArray(new String[0]));

		}
	}

	private void signTransaction(Address paymentAddress, boolean signWithPolicy) throws Exception {
		{

			String skeyFilename = prefixFilename(UUID.randomUUID().toString() + "." + PAYMENT_SKEY_FILENAME);
			fileUtil.writeFile(skeyFilename, paymentAddress.getSkey());

			ArrayList<String> cmd = new ArrayList<String>();
			cmd.addAll(List.of(cardanoCliCmd));
			cmd.add("transaction");
			cmd.add("sign");

			cmd.add("--signing-key-file");
			cmd.add(skeyFilename);

			if (signWithPolicy) {
				cmd.add("--signing-key-file");
				cmd.add(prefixFilename(POLICY_SKEY_FILENAME));
			}

			cmd.addAll(List.of(networkMagicArgs));

			cmd.add("--tx-body-file");
			cmd.add(prefixFilename(TRANSACTION_UNSIGNED_FILENAME));

			cmd.add("--out-file");
			cmd.add(prefixFilename(TRANSACTION_SIGNED_FILENAME));

			ProcessUtil.runCommand(cmd.toArray(new String[0]));

			fileUtil.removeFile(skeyFilename);

		}
	}

	private String formatCurrency(String policyId, String assetName) {
		if (StringUtils.isBlank(assetName)) {
			return policyId;
		} else {
			return policyId + "." + Hex.encodeHexString(assetName.getBytes(StandardCharsets.UTF_8));
		}
	}

}
