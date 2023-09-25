function updateHunt() {
	$.ajax({
		url: "api/currentCharlyHolders",
		success: function(result) {
			let place = 0;

			if (result.length) {
				$('#loading').hide();
			} else {
				$('#loading').show();
			}

			var blacklist = [
				'stake1u94vpc75fv6mq4vcupew454mf97wygg54shprlnwy8f5r5spul7ju',
				'addr1v9gs0trlcmyty7jakcewjs3h00a7xrzyd5wnyfrpeg4wjts0ugx63',
				'stake1u8wmu7jc0e4a6fn5haflczfjy6aagwhsxh6w5p7hsyt8jeshhy0rn',
				'stake1uyvy8zt0k5qc06xp0mkjg3jvuttm4knyrussfhz4xfetu7g2h4gr9',
				'stake1uxv79q8x372qlgaw3e7wnl0x0nvke46ws9fpwfhzkn8g58cev8y9t',
				'stake1u82kr0vgaapv07cnhr42hjacyj2h56qje2eqk3pwyzngf4q78j44e',
				'stake1ux7m0jrvdegy4c4t8c63a2ya4vpgwt0wjp8fud2z93hpgkq902u9l',
				'addr1vx4k40tqxn9lxm5yjpfx62hq8h8pfmwnexfyqlegmavhsmgxygm82',
				'addr1v9y8fc3qhqj4tcxe2uy4u4285euz9v5r7ylxwgk2xnuucpswdsnrs',
				'stake1uxfghh5csenhvlegfnff6x5uvcxhanksv26mqcv9mxundlgc8hjrj',
				'stake1u89tnj258vkk4p9fnd226e7lulh3xh58mvl66uzarzgkrxq7xz24l',
				'stake1uy6k2a43zes2f652drqse959ta2k8lze4c9d9h9ph4cs48szzl0sr',
				'stake1uyq4g3vqed986la2h7ywavup76xjr0kpfew30u99quw6w4qjxjucm',
				'addr1qxrqhlzv57pqpm7yvryr4ghgeexeqq66j09pfrw4ck69vjsnpr0py2gak038u9ql8yrwkutpsylpsngyfqjrk8kfrv9sf65934',
			];

			result = result.filter(e => blacklist.indexOf(e.address) === -1).sort((e1, e2) => e2.amount - e1.amount);
			let max = Math.max.apply(Math, result.map(e => e.amount));
			let min = Math.min.apply(Math, result.map(e => e.amount));

			minTokens = max;
			$('#half-amount').text((Math.floor(max / 2 / 1000000 * 1) / 1) + 'm')
			$('#full-amount').text((Math.ceil(max / 1000000 * 1) / 1) + 'm')

			$('#total-holders-amount').text(result.length);

			for (row of result) {

				if (!$('#' + row.address).length) {
					$('.hunter-field').append(
						`
						<div class="hunter-lane" id="${row.address}">
							<div class="hunter">
								<span class="top"><a target="_blank" href="https://cardanoscan.io/search?filter=all&value=${row.address}">${row.address}</a></span>
								<img src="/images/walking_.gif">
								<span class="bottom"></span>
							</div>
						</div>
						`
					);
				}

				$('#' + row.address).find('.hunter').css('left', row.amount / minTokens * 100 + '%');
				$('#' + row.address).data('place', place);
				$('#' + row.address).find('.hunter .bottom').text((Math.floor(row.amount / 1000000 * 1000) / 1000) + 'm');

				place++;
			}

			let existingGroups = result.map(r => r.address);
			$('.hunter-lane').each((i, el) => {
				if (existingGroups.indexOf($(el).attr('id')) === -1) {
					$(el).remove();
				}
			});

			let headingHeight = 56;
			let laneHeight = 140;
			// set total height
			$('.hunter-field').css('height', $('.hunter-lane').length * laneHeight + headingHeight);
			// set vertical positions
			$('.hunter-lane').each((i, el) => {
				$(el).css('top', $(el).data('place') * laneHeight + headingHeight);
			});


		}
	});
}
setInterval(updateHunt, 10000);
updateHunt();


