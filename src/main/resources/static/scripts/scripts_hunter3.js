function updateHunt() {
	$.ajax({
		url: "api/hunterRows",
		success: function(result) {

			result = result.sort((r1, r2) => (r2.count - r1.count) || (r2.rank - r1.rank));

			let place = 0;

			if (result.length) {
				$('#loading').hide();
				$('.hunter-lane-header').show();

			} else {
				$('#loading').show();
				$('.hunter-lane-header').hide();
			}


			let maxTransactions = Math.max(...result.map(r => r.count));
			$('#half-amount').text((maxTransactions / 2).toLocaleString())
			$('#max-amount').text(maxTransactions.toLocaleString());

			for (row of result) {
				if ($('#' + row.stakeAddress).length) {
					$('#' + row.stakeAddress).find('.hunter').css('left', row.count / maxTransactions * 100 + '%');
					$('#' + row.stakeAddress).data('place', place);
					$('#' + row.stakeAddress).find('.hunter .bottom').text(row.count.toLocaleString());

					let hunter = $('#' + row.stakeAddress).find('.hunter');
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
	let el = $('#countdown_start [data-countdown]');
	let end = Date.parse($(el).data('countdown') + 'Z');
	let now = new Date().getTime();
	let secondsLeft = Math.max((end - now) / 1000, 0);
	if (secondsLeft > 0) {
		$('.hunter-field-boundary').hide();
		$('#countdown_end').hide();
	} else {
		$('.hunter-field-boundary').show();
		//$('#countdown_start').hide();
		$('#countdown_end').show();
	}
}

setInterval(reveal, 1000);
reveal();


