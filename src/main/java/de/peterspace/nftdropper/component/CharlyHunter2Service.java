package de.peterspace.nftdropper.component;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import de.peterspace.nftdropper.TrackExecutionTime;
import de.peterspace.nftdropper.cardano.CardanoCli;
import de.peterspace.nftdropper.cardano.CardanoDbSyncClient;
import de.peterspace.nftdropper.model.Address;
import de.peterspace.nftdropper.model.Hunter2SnapshotRow;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CharlyHunter2Service {

	@Value("${seller.address}")
	private String sellerAddress;

	@Value("${charly.token}")
	private String charlyToken;

	@Value("${charly.hunter2.start}")
	private String hunterStartString;
	@Getter
	private Instant hunterStartTimestamp;

	@Value("${charly.hunter2.end}")
	private String hunterEndString;
	private Instant hunterEndTimestamp;

	private Address paymentAddress;

	private final CardanoDbSyncClient cardanoDbSyncClient;
	private final CardanoCli cardanoCli;

	@Getter
	private List<Hunter2SnapshotRow> currentToplist = new ArrayList<>();

	@PostConstruct
	public void init() throws Exception {
		if (StringUtils.isBlank(charlyToken)) {
			return;
		}
		hunterStartTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mmz").parse(hunterStartString + "UTC").toInstant();
		hunterEndTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mmz").parse(hunterEndString + "UTC").toInstant();
		paymentAddress = cardanoCli.createPaymentAddress();
	}

	@Scheduled(fixedRate = 1_000 * 60, initialDelay = 0)
	@TrackExecutionTime
	public void updateSnapshot() throws Exception {
		if (StringUtils.isBlank(charlyToken)) {
			return;
		}

		currentToplist = cardanoDbSyncClient.createHunter2Snapshot(paymentAddress, sellerAddress, hunterStartTimestamp, hunterEndTimestamp);

		if (Instant.now().isAfter(hunterEndTimestamp)) {
			for (int i = 0; i < Math.min(5, currentToplist.size()); i++) {
				currentToplist.get(i).setRank(i + 1);
			}
		}

		System.out.println(currentToplist);
	}

}
