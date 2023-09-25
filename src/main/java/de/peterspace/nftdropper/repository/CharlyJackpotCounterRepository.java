package de.peterspace.nftdropper.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import de.peterspace.nftdropper.model.CharlyJackpotCounter;

@Repository
public interface CharlyJackpotCounterRepository extends JpaRepository<CharlyJackpotCounter, Long> {

}