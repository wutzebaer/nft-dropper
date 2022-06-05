package de.peterspace.nftdropper.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class CardanoUtil {

	public static long currentSlot() {
		return System.currentTimeMillis() / 1000 - 1596491091 + 4924800;
	}

}
