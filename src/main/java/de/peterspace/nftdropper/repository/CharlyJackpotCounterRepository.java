package de.peterspace.nftdropper.repository;

import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import de.peterspace.nftdropper.model.CharlyJackpotCounter;

@Repository
public interface CharlyJackpotCounterRepository extends PagingAndSortingRepository<CharlyJackpotCounter, Long> {

}