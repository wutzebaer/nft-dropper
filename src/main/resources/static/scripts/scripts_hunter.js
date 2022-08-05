function updateHunt() {
	$.ajax({
		url: "api/currentHunterValues",
		success: function(result) {
			// $('.hunter-lane, #loading').remove()
			$('#loading').remove();
			let place = 0;
			for (row of result.hunterSnapshotRows) {

				if ($('#' + row.group).length) {
					$('#' + row.group).find('.hunter').css('left', row.quantity / minTokens * 100 + '%');
					$('#' + row.group).data('place', place);

					let hunter = $('#' + row.group).find('.hunter');
					if (row.rank && hunter.find('.rank').length === 0) {
						hunter.append(`<img class="rank" src="/images/rank${row.rank}.png">`);
						setTimeout(() => hunter.append(`<img class="firework" src="/images/firework.gif">`), 2000);
						setTimeout(() => hunter.find('.firework').remove(), 5000);
					}
				} else {
					$('.hunter-field').append(
						`
						<div class="hunter-lane" id="${row.group}" data-place="${place}">
							<div class="hunter" style="left: 0%">
								<span class="top">${row.handle || row.address}</span>
								<img src="/images/output-onlinegiftools.gif">
								<span class="bottom">${(Math.floor(row.quantity / 1000000 * 1000) / 1000)}m</span>
							</div>
						</div>
						`
					);
				}

				place++;
			}



			let existingGroups = result.hunterSnapshotRows.map(r => r.group);

			$('.hunter-lane').each((i, el) => {
				if (existingGroups.indexOf($(el).attr('id')) === -1) {
					$(el).remove();
				}
			});

			let headingHeight = 36;
			let laneHeight = 130;
			$('.hunter-field').css('height', $('.hunter-lane').length * laneHeight + headingHeight);
			$('.hunter-lane').each((i, el) => {
				$(el).css('top', $(el).data('place') * laneHeight + headingHeight);
			});

		}
	});
}

setInterval(updateHunt, 1000);
updateHunt();