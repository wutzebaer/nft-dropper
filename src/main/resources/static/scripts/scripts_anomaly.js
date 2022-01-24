async function sha256(message) {
	try {
	// encode as UTF-8
	const msgBuffer = new TextEncoder().encode(message);

	// hash the message
	const hashBuffer = await crypto.subtle.digest('SHA-256', msgBuffer);

	// convert ArrayBuffer to Array
	const hashArray = Array.from(new Uint8Array(hashBuffer));

	// convert bytes to hex string
	const hashHex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
	return hashHex;
	}
	catch (e) {
	   // Anweisungen für jeden Fehler
	   alert(e); // Fehler-Objekt an die Error-Funktion geben
	}
}

$('.shop-item').mouseenter(e => {
	let shopItem = $(e.currentTarget);
	startDecrypt(shopItem)
});
$('.shop-item').mouseleave(e => {
	let shopItem = $(e.currentTarget);
	if (!shopItem.find('.form-check-input').prop("checked")) {
		stopDecrypt(shopItem)
	}
});

$('.hover-gif').click(e => {
	let shopItem = $(e.currentTarget).closest('.shop-item');
	if (shopItem.attr('running') === 'true' && shopItem.find('.form-check-input').prop("checked") == false) {
		shopItem.find('.form-check-input').prop("checked", true)
	}
	else if (shopItem.attr('running') === 'true') {
		stopDecrypt(shopItem)
		shopItem.find('.form-check-input').prop("checked", false)
	} else {
		startDecrypt(shopItem)
		shopItem.find('.form-check-input').prop("checked", true)
	}
});


$('.shop-item .form-check').click(e => {
	let shopItem = $(e.currentTarget).closest('.shop-item');
	let checked = $(e.currentTarget).find('.form-check-input').prop("checked");
	if (!checked) {
		stopDecrypt(shopItem)
	} else {
		startDecrypt(shopItem)
	}
});

function startDecrypt(shopItem) {
	if (shopItem.attr('running') === 'true' || shopItem.attr('decrypted') === 'true' || shopItem.attr('escaped') === 'true') {
		return;
	}
	shopItem.attr('running', true);
	shopItem.find('.progress').show();
	shopItem.find('.form-check').show();
	calculateKey(shopItem);
}

function stopDecrypt(shopItem) {
	shopItem.attr('running', false);
}

function calculateKey(shopItem) {
	const prime = 769;
	let key = shopItem.find('.key');
	sha256(key.attr('i')).then(res => {
		let attempt = parseInt(key.attr('i'));
		if (attempt % (prime * 1000) == 0) {
			shopItem.find('.progress-bar').width((attempt / 1000_000_000 * 100) + '%');
		}
		if (res === key.attr('key')) {
			key.text(key.attr('i') / 1000000)
			shopItem.find('.progress').hide();
			shopItem.find('.form-check').hide();
			shopItem.attr('decrypted', true);

			$.ajax({
				url: "api/tokenAddress?assetName=" + shopItem.attr('assetName') + "&key=" + key.attr('i'),
				success: function(result) {
					shopItem.find('.address-value').text(result);
					shopItem.find('.address .spinner').hide();
				}
			});

		} else {
			key.text(res)
			key.attr('i', attempt + prime);
			if (shopItem.attr('running') === 'true') {
				calculateKey(shopItem);
			}
		}
	});
}



function updateEscapes() {
	$.ajax({
		url: "api/policyTokens",
		success: function(result) {
			result.forEach(e => {
				$('[assetname*=' + e.assetName + '][escaped=false]').each((i, el) => {
					let shopItem = $(el);
					let metaData = JSON.parse(e.json);
					shopItem.attr('escaped', true);
					shopItem.find('.static').attr('src', 'https://ipfs.cardano-tools.io/ipfs/' + metaData.image.replace('ipfs://', ''));
					shopItem.one('mouseenter', () => {
						shopItem.find('.animation').replaceWith('<video class="animation" src="' + 'https://ipfs.cardano-tools.io/ipfs/' + metaData.files[0].src.replace('ipfs://', '') + '" autoplay loop>');
					});
				});

			});
		}
	});
}
setInterval(function() {
	updateEscapes()
}, 1000);
updateEscapes();


$('.filter input').on('input', e => {
	let filter = e.target.value.toUpperCase();
	$('.shop-item').each((i, el) => {
		if ($(el).text().toUpperCase().indexOf(filter) === -1) {
			$(el).hide();
		} else {
			$(el).show();
		}
	});
})