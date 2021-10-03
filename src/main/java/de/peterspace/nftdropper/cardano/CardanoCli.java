package de.peterspace.nftdropper.cardano;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

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

	private static final String TRANSACTION_UNSIGNED_FILENAME = "transaction.unsigned";
	private static final String TRANSACTION_METADATA_JSON_FILENAME = "transactionMetadata.json";
	private static final String TRANSACTION_SIGNED_FILENAME = "transaction.signed";

	@Value("${NETWORK}")
	private String network;

	private final CardanoNode cardanoNode;
	private final FileUtil fileUtil;

	@Value("${working.dir}")
	private String workingDir;

	private String[] cardanoCliCmd;
	private String[] networkMagicArgs;

	@PostConstruct
	public void init() throws Exception {

		// @formatter:off
        cardanoCliCmd = new String[] {
                "docker", "exec",
                "-w", "/work",
                "-e", "CARDANO_NODE_SOCKET_PATH=/ipc/node.socket",
                cardanoNode.getContainerName(),
                "cardano-cli"
        };
        // @formatter:on
		this.networkMagicArgs = cardanoNode.getNetworkMagicArgs();

		ArrayList<String> cmd = new ArrayList<String>();
		cmd.addAll(List.of(cardanoCliCmd));
		cmd.add("query");
		cmd.add("protocol-parameters");
		cmd.add("--out-file");
		cmd.add("protocol.json");
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

		String skeyFilename = "payment.skey";
		String vkeyFilename = "payment.vkey";
		String addressFilename = "payment.addr";

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

		Address address = new Address(addressLiteral, skey, vkey);
		return address;
	}

	public Policy createPolicy(String vkey, int days) throws Exception {

		String policyFilename = "policy.script";
		String policyIdFilename = "policy.id";

		String policyString;
		if (!fileUtil.exists(policyFilename)) {

			long secondsToLive = 60 * 60 * 24 * days;
			long dueSlot = queryTip() + secondsToLive;

			String vkeyFilename = filename("vkey");
			fileUtil.writeFile(vkeyFilename, vkey);

			// address hash
			ArrayList<String> cmd1 = new ArrayList<String>();
			cmd1.addAll(List.of(cardanoCliCmd));
			cmd1.add("address");
			cmd1.add("key-hash");
			cmd1.add("--payment-verification-key-file");
			cmd1.add(vkeyFilename);
			String keyHash = ProcessUtil.runCommand(cmd1.toArray(new String[0]));
			fileUtil.removeFile(vkeyFilename);

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

	private String filename(String ext) {
		return UUID.randomUUID().toString() + "." + ext;
	}

	public String mint(List<TransactionInputs> transactionInputs, TransactionOutputs transactionOutputs, JSONObject metaData, String changeAddress, Address paymentAddress, Policy policy) throws Exception {
		try {

			buildTransaction(transactionInputs, transactionOutputs, metaData, changeAddress, policy);
			signTransaction();
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
		cmd.add(TRANSACTION_SIGNED_FILENAME);

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
			cmd.add(TRANSACTION_SIGNED_FILENAME);
			txId = ProcessUtil.runCommand(cmd.toArray(new String[0]));
		}
		return txId;
	}

	private void buildTransaction(List<TransactionInputs> transactionInputs, TransactionOutputs transactionOutputs, JSONObject metaData, String changeAddress, Policy policy) throws Exception {
		{

			ArrayList<String> cmd = new ArrayList<String>();
			cmd.addAll(List.of(cardanoCliCmd));

			cmd.add("transaction");
			cmd.add("build");

			for (TransactionInputs utxo : transactionInputs) {
				cmd.add("--tx-in");
				cmd.add(utxo.getTxhash() + "#" + utxo.getTxix());
			}

			for (String a : transactionOutputs.toCliFormat()) {
				cmd.add("--tx-out");
				cmd.add(a);
			}

			List<Entry<String, Long>> assetEntries = transactionOutputs.getOutputs().values()
					.stream()
					.flatMap(a -> a.entrySet().stream())
					.filter(e -> !StringUtils.isBlank(e.getKey()))
					.collect(Collectors.toList());
			List<String> mints = new ArrayList<String>();
			for (Entry<String, Long> assetEntry : assetEntries) {
				mints.add(String.format("%d %s", assetEntry.getValue(), assetEntry.getKey()));
			}
			if (mints.size() > 0) {
				cmd.add("--mint");
				cmd.add(StringUtils.join(mints, "+"));
				cmd.add("--minting-script-file");
				cmd.add("policy.script");
			}

			if (metaData != null) {
				fileUtil.writeFile(TRANSACTION_METADATA_JSON_FILENAME, metaData.toString(3));
				cmd.add("--json-metadata-no-schema");
				cmd.add("--metadata-json-file");
				cmd.add(TRANSACTION_METADATA_JSON_FILENAME);
			}

			cmd.add("--change-address");
			cmd.add(changeAddress);

			cmd.addAll(List.of(networkMagicArgs));
			cmd.add("--alonzo-era");

			cmd.add("--out-file");
			cmd.add(TRANSACTION_UNSIGNED_FILENAME);

			long maxSlot = new JSONObject(policy.getPolicy()).getJSONArray("scripts").getJSONObject(0).getLong("slot");
			cmd.add("--invalid-hereafter");
			cmd.add("" + maxSlot);

			ProcessUtil.runCommand(cmd.toArray(new String[0]));

		}
	}

	private void signTransaction() throws Exception {
		{
			ArrayList<String> cmd = new ArrayList<String>();
			cmd.addAll(List.of(cardanoCliCmd));
			cmd.add("transaction");
			cmd.add("sign");

			cmd.add("--signing-key-file");
			cmd.add("payment.skey");

			cmd.addAll(List.of(networkMagicArgs));

			cmd.add("--tx-body-file");
			cmd.add(TRANSACTION_UNSIGNED_FILENAME);

			cmd.add("--out-file");
			cmd.add(TRANSACTION_SIGNED_FILENAME);

			ProcessUtil.runCommand(cmd.toArray(new String[0]));

		}
	}

}
