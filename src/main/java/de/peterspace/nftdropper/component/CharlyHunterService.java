package de.peterspace.nftdropper.component;

import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

		if (hunterSnapshotRepository.count() == 0) {
			updateSnapshot();
		}

		initialHunterSnapshot = toMap(hunterSnapshotRepository.findFirstByOrderByIdAsc());
		updateDifference(hunterSnapshotRepository.findFirstByOrderByIdDesc());
	}

	@Scheduled(cron = "*/60 * * * * *")
	@TrackExecutionTime
	public void updateSnapshot() throws Exception {
		if (StringUtils.isBlank(charlyToken)) {
			return;
		}

		HunterSnapshot hunterSnapshot = cardanoDbSyncClient.createHunterSnapshot();
		hunterSnapshotRepository.save(hunterSnapshot);
		updateDifference(hunterSnapshot);
	}

	private void updateDifference(HunterSnapshot hunterSnapshot) throws Exception {
		for (HunterSnapshotRow row : hunterSnapshot.getHunterSnapshotRows()) {
			HunterSnapshotRow initialSnapshot = initialHunterSnapshot.get(row.getGroup());
			if (initialSnapshot != null) {
				long quantitiy = row.getQuantity();
				long newQuantity = Math.max(quantitiy - initialSnapshot.getQuantity(), 0);
				row.setQuantity(newQuantity);
			}
		}
		hunterSnapshot.getHunterSnapshotRows().removeIf(r -> r.getQuantity() == 0);
		hunterSnapshot.getHunterSnapshotRows().sort(Comparator.comparing(HunterSnapshotRow::getQuantity).reversed());

		currentDifference = hunterSnapshot;
	}

	private Map<String, HunterSnapshotRow> toMap(HunterSnapshot hunterSnapshot) {
		return hunterSnapshot.getHunterSnapshotRows()
				.stream()
				.collect(Collectors.toMap(r -> r.getGroup(), Function.identity()));
	}

}
