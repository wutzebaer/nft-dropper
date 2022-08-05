package de.peterspace.nftdropper.component;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.google.common.base.Objects;

import de.peterspace.nftdropper.TrackExecutionTime;
import de.peterspace.nftdropper.cardano.CardanoDbSyncClient;
import de.peterspace.nftdropper.model.HunterSnapshot;
import de.peterspace.nftdropper.model.HunterSnapshotRow;
import de.peterspace.nftdropper.repository.HunterSnapshotRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class CharlyHunterService {

	@Value("${charly.token}")
	private String charlyToken;

	@Value("${charly.hunter.min}")
	private int minTokens;

	@Value("${charly.hunter.start}")
	private String hunterStartString;
	private long hunterStartTimestamp;

	// Injects
	private final CardanoDbSyncClient cardanoDbSyncClient;
	private final HunterSnapshotRepository hunterSnapshotRepository;

	private Map<String, HunterSnapshotRow> initialHunterSnapshot;

	@Getter
	private HunterSnapshot currentDifference;

	@PostConstruct
	public void init() throws Exception {
		if (StringUtils.isBlank(charlyToken)) {
			return;
		}
		hunterStartTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mmz").parse(hunterStartString + "UTC").getTime();
		currentDifference = new HunterSnapshot();
		currentDifference.setHunterSnapshotRows(List.of());
		updateSnapshot();
	}

	@Scheduled(cron = "*/20 * * * * *")
	@TrackExecutionTime
	public void updateSnapshot() throws Exception {
		if (StringUtils.isBlank(charlyToken) || (System.currentTimeMillis() < hunterStartTimestamp)) {
			return;
		}

		HunterSnapshot hunterSnapshot = cardanoDbSyncClient.createHunterSnapshot();

		if (initialHunterSnapshot == null) {
			log.info("Setting initialHunterSnapshot");
			if (hunterSnapshotRepository.count() == 0) {
				log.info("Saving first snapshot");
				hunterSnapshotRepository.save(hunterSnapshot);
			}
			initialHunterSnapshot = toMap(hunterSnapshotRepository.findFirstByOrderByIdAsc());
		}

		if (!Objects.equal(hunterSnapshot, hunterSnapshotRepository.findFirstByOrderByIdDesc())) {
			log.info("Saving new snapshot");
			hunterSnapshotRepository.save(hunterSnapshot);
		} else {
			log.info("Snapshot unchanged");
		}
		updateDifference(hunterSnapshot);
	}

	private void updateDifference(HunterSnapshot hunterSnapshot) throws Exception {
		for (HunterSnapshotRow row : hunterSnapshot.getHunterSnapshotRows()) {
			HunterSnapshotRow initialSnapshot = initialHunterSnapshot.get(row.getGroup());
			if (initialSnapshot != null) {
				long quantitiy = row.getQuantity();
				long newQuantity = Math.min(Math.max(quantitiy - initialSnapshot.getQuantity(), 0), minTokens);
				row.setQuantity(newQuantity);
			}
		}
		hunterSnapshot.getHunterSnapshotRows().removeIf(r -> r.getQuantity() == 0);

		List<String> toplist = hunterSnapshotRepository.getToplist(minTokens);
		for (int i = 0; i < toplist.size(); i++) {
			String group = toplist.get(i);
			hunterSnapshot.getHunterSnapshotRows().stream().filter(row -> Objects.equal(row.getGroup(), group)).findFirst().get().setRank(i + 1);
		}

		hunterSnapshot.getHunterSnapshotRows().sort(Comparator.comparing(HunterSnapshotRow::getQuantity).reversed().thenComparing(HunterSnapshotRow::getRank));

		currentDifference = hunterSnapshot;
	}

	private Map<String, HunterSnapshotRow> toMap(HunterSnapshot hunterSnapshot) {
		return hunterSnapshot.getHunterSnapshotRows()
				.stream()
				.collect(Collectors.toMap(r -> r.getGroup(), Function.identity()));
	}

}
