function updateHunt() {
	$.ajax({
		url: "api/currentHunter2Values",
		success: function(result) {
			let place = 0;

			if (result.length) {
				$('#loading').hide();
			} else {
				$('#loading').show();
			}

			let maxTransactions = Math.max(...result.map(r => r.count));
			$('#half-amount').text(maxTransactions / 2)
			$('#max-amount').text(maxTransactions);

			for (row of result) {
				if ($('#' + row.stakeAddress).length) {
					$('#' + row.stakeAddress).find('.hunter').css('left', row.count / maxTransactions * 100 + '%');
					$('#' + row.stakeAddress).data('place', place);
					$('#' + row.stakeAddress).find('.hunter .bottom').text(row.count);
				} else {
					$('.hunter-field').append(
						`
						<div class="hunter-lane" id="${row.stakeAddress}" data-place="${place}">
							<div class="hunter" style="left: 0%">
								<span class="top"><a target="_blank" href="https://cardanoscan.io/search?filter=all&value=${row.stakeAddress}">${row.handle || row.stakeAddress}</a></span>
								<img src="/images/walking_.gif">
								<span class="bottom">${row.count}</span>
							</div>
						</div>
						`
					);
				}
				place++;
			}

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