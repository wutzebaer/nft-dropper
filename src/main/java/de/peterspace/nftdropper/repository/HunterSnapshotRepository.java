package de.peterspace.nftdropper.repository;

import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import de.peterspace.nftdropper.model.HunterSnapshot;

@Repository
public interface HunterSnapshotRepository extends PagingAndSortingRepository<HunterSnapshot, Long> {
	HunterSnapshot findFirstByOrderByIdAsc();
	HunterSnapshot findFirstByOrderByIdDesc();
}