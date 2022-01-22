package de.peterspace.nftdropper.repository;

import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import de.peterspace.nftdropper.model.Address;

@Repository
public interface AddressRepository extends PagingAndSortingRepository<Address, String> {

	public Address findByAssetName(String assetName);

}