package de.peterspace.nftdropper.cardano;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class CardanoNode {

	@Value("${NETWORK}")
	private String network;

	@Value("${cardano-node.ipc-volume-name}")
	private String ipcVolumeName;

	@Value("${cardano-node.version}")
	private String nodeVersion;

	@Getter
	private String[] networkMagicArgs;

	@Getter
	private String donationAddress;

	@Getter
	private String era;

	@PostConstruct
	public void init() throws Exception {

		// determine network
		if (network.equals("preview")) {
			networkMagicArgs = new String[] { "--testnet-magic", "2" };
			donationAddress = "addr_test1qp8cprhse9pnnv7f4l3n6pj0afq2hjm6f7r2205dz0583ed6zj0zugmep9lxtuxq8unn85csx9g70ugq6dklmvq6pv3qa0n8cl";
		} else if (network.equals("mainnet")) {
			networkMagicArgs = new String[] { "--mainnet" };
			donationAddress = "addr1q9h7988xmmpz2y50rg2n9fw6jd5rq95t8q84k4q6ne403nxahea9slntm5n8f06nlsynyf4m6sa0qd05agra0qgk09nq96rqh9";
		} else {
			throw new RuntimeException("Network must be preview or mainnet");
		}

		
		// ensure node is synced
		while (true) {
			try {
				JSONObject tip = queryTip();
				double syncProgress = tip.getDouble("syncProgress");
				log.info("Synced {}", syncProgress);
				if (syncProgress == 100) {
					era = tip.getString("era");
					break;
				}
			} catch (Exception e) {
				log.error("Not synced: {}", e.getMessage());
			}
			Thread.sleep(10000);
		}
	}

	private JSONObject queryTip() throws Exception {
		ArrayList<String> cmd = new ArrayList<String>();

		cmd.add("docker");
		cmd.add("run");

		cmd.add("--rm");

		cmd.add("--entrypoint");
		cmd.add("cardano-cli");

		cmd.add("-v");
		cmd.add(ipcVolumeName + ":/ipc");

		cmd.add("-e");
		cmd.add("CARDANO_NODE_SOCKET_PATH=/ipc/node.socket");

		cmd.add("inputoutput/cardano-node:" + nodeVersion);

		cmd.add("query");
		cmd.add("tip");

		cmd.addAll(List.of(networkMagicArgs));
		String jsonString = ProcessUtil.runCommand(cmd.toArray(new String[0]));
		JSONObject jsonObject = new JSONObject(jsonString);
		return jsonObject;
	}

}
