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
				'stake1uy6k2a43zes2f652drqse959ta2k8lze4c9d9h9ph4cs48szzl0sr'
			];

			result = result.filter(e => blacklist.indexOf(e.group) === -1);
			let max = Math.max.apply(Math, result.map(e => e.quantity));
			let min = Math.min.apply(Math, result.map(e => e.quantity));

			minTokens = max;
			$('#half-amount').text((Math.floor(max / 2 / 1000000 * 1) / 1) + 'm')
			$('#full-amount').text((Math.ceil(max / 1000000 * 1) / 1) + 'm')

			$('#total-holders-amount').text(result.length);

			for (row of result) {

				if ($('#' + row.group).length) {
					$('#' + row.group).find('.hunter').css('left', row.quantity / minTokens * 100 + '%');
					$('#' + row.group).data('place', place);
					$('#' + row.group).find('.hunter .bottom').text((Math.floor(row.quantity / 1000000 * 1000) / 1000) + 'm');

					let hunter = $('#' + row.group).find('.hunter');
					if (row.rank) {
						if (hunter.find('.rank').length === 0) {
							hunter.append(`<img class="rank" src="/images/rank${row.rank}.png">`);
							setTimeout(() => hunter.append(`<img class="firework" src="/images/firework.gif">`), 2000);
							setTimeout(() => hunter.find('.firework').remove(), 5000);
						}
					} else {
						hunter.find('.rank').remove();
					}
				} else {
					$('.hunter-field').append(
						`
						<div class="hunter-lane" id="${row.group}" data-place="${place}">
							<div class="hunter" style="left: 0%">
								<span class="top"><a target="_blank" href="https://cardanoscan.io/search?filter=all&value=${row.address}">${row.handle || row.address}</a></span>
								<img src="/images/walking_.gif">
								<span class="bottom">${(Math.floor(row.quantity / 1000000 * 1000) / 1000)}m</span>
							</div>
						</div>
						`
					);
				}

				place++;
			}



			let existingGroups = result.map(r => r.group);

			$('.hunter-lane').each((i, el) => {
				if (existingGroups.indexOf($(el).attr('id')) === -1) {
					$(el).remove();
				}
			});

			let headingHeight = 56;
			let laneHeight = 140;
			$('.hunter-field').css('height', $('.hunter-lane').length * laneHeight + headingHeight);
			$('.hunter-lane').each((i, el) => {
				$(el).css('top', $(el).data('place') * laneHeight + headingHeight);
			});


		}
	});
}
setInterval(updateHunt, 1000);
updateHunt();

function reveal() {
	let el = $('[data-countdown]');
	let end = Date.parse($(el).data('countdown') + 'Z');
	let now = new Date().getTime();
	let secondsLeft = Math.max((end - now) / 1000, 0);
	if (secondsLeft > 0) {
		$('.hunter-field-boundary').hide();
	} else {
		$('.hunter-field-boundary').show();
	}
}

setInterval(reveal, 1000);
reveal();