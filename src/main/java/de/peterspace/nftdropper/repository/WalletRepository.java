package de.peterspace.nftdropper.repository;

import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import de.peterspace.nftdropper.model.Address;
import de.peterspace.nftdropper.model.Wallet;

@Repository
public interface WalletRepository extends PagingAndSortingRepository<Wallet, Long> {

}