package de.peterspace.nftdropper.component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

import de.peterspace.nftdropper.TrackExecutionTime;
import de.peterspace.nftdropper.cardano.CardanoDbSyncClient;
import de.peterspace.nftdropper.model.HunterSnapshotRow;
import de.peterspace.nftdropper.repository.HunterSnapshotRowtRepository;
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
	private final HunterSnapshotRowtRepository hunterSnapshotRepository;

	@Getter
	private List<HunterSnapshotRow> currentToplist = new ArrayList<>();

	@Getter
	private List<HunterSnapshotRow> chainSnapshot = new ArrayList<>();

	@PostConstruct
	public void init() throws Exception {
		if (StringUtils.isBlank(charlyToken)) {
			return;
		}
		hunterStartTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mmz").parse(hunterStartString + "UTC").getTime();
	}

	@Scheduled(fixedRate = 1_000 * 60 * 30, initialDelay = 0)
	@TrackExecutionTime
	public void updateSnapshot() throws Exception {
		if (StringUtils.isBlank(charlyToken)) {
			return;
		}

		if (this.chainSnapshot.isEmpty()) {
			log.info("Initializing charly chainSnapshot");
		}

		this.chainSnapshot = cardanoDbSyncClient.createHunterSnapshot();

		if (System.currentTimeMillis() < hunterStartTimestamp) {
			return;
		}

		Map<String, HunterSnapshotRow> localSnapshot = toMap(hunterSnapshotRepository.findAll());

		for (HunterSnapshotRow chainRow : chainSnapshot) {
			HunterSnapshotRow localRow = localSnapshot.computeIfAbsent(chainRow.getGroup(), c -> {
				HunterSnapshotRow newRow = new HunterSnapshotRow();
				newRow.setAddress(chainRow.getAddress());
				newRow.setGroup(chainRow.getGroup());
				newRow.setHandle(chainRow.getHandle());
				newRow.setQuantity(0);
				newRow.setStartQuantity(chainRow.getQuantity());
				newRow.setTimestamp(new Date());
				hunterSnapshotRepository.save(newRow);
				return newRow;
			});

			long newQuantity = Math.min(Math.max(chainRow.getQuantity() - localRow.getStartQuantity(), 0), minTokens);
			if (newQuantity != localRow.getQuantity()) {
				log.info("{} raised tokens to {}", localRow.getGroup(), newQuantity);
				localRow.setQuantity(newQuantity);
				localRow.setTimestamp(new Date());
				hunterSnapshotRepository.save(localRow);
			}
		}

		localSnapshot.values().retainAll(chainSnapshot);
		localSnapshot.values().removeIf(v -> v.getQuantity() == 0);

		List<HunterSnapshotRow> newToplist = new ArrayList<>(localSnapshot.values());
		newToplist.sort(Comparator
				.comparing(HunterSnapshotRow::getQuantity, Comparator.reverseOrder())
				.thenComparing(HunterSnapshotRow::getTimestamp));

		for (int i = 0; i < newToplist.size(); i++) {
			HunterSnapshotRow row = newToplist.get(i);
			if (row.getQuantity() >= minTokens) {
				row.setRank(i + 1);
			}
		}

		currentToplist = newToplist;
	}

	private Map<String, HunterSnapshotRow> toMap(List<HunterSnapshotRow> rows) {
		return rows
				.stream()
				.collect(Collectors.toMap(r -> r.getGroup(), Function.identity()));
	}

}
