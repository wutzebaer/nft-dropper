package de.peterspace.nftdropper.repository;

import java.util.List;

import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import de.peterspace.nftdropper.model.Address;

@Repository
public interface AddressRepository extends PagingAndSortingRepository<Address, String> {

}