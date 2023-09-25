package de.peterspace.nftdropper.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import de.peterspace.nftdropper.model.Address;

@Repository
public interface AddressRepository extends JpaRepository<Address, String> {

}