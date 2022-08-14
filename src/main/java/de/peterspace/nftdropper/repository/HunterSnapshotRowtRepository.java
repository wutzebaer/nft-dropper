package de.peterspace.nftdropper.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import de.peterspace.nftdropper.model.HunterSnapshotRow;

@Repository
public interface HunterSnapshotRowtRepository extends PagingAndSortingRepository<HunterSnapshotRow, String> {

	List<HunterSnapshotRow> findAll();

	@Query(value = ""
			+ "SELECT min(hr.\"group\") "
			+ "FROM HUNTER_SNAPSHOT_ROW hr "
			+ "LEFT JOIN HUNTER_SNAPSHOT_ROW first_hr ON first_hr.\"group\"=hr.\"group\" AND first_hr.HUNTER_SNAPSHOT_ID=(SELECT min(id) FROM HUNTER_SNAPSHOT) "
			+ "WHERE (hr.QUANTITY - COALESCE(first_hr.QUANTITY,0)) >= ?1 "
			+ "GROUP BY hr.\"group\" "
			+ "ORDER BY min(hr.HUNTER_SNAPSHOT_ID), min(hr.\"group\")", nativeQuery = true)
	List<String> getToplist(long minquantity);
}