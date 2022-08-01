function updateHunt() {
	$.ajax({
		url: "api/currentHunterValues",
		success: function(result) {
			// $('.hunter-lane, #loading').remove()
			$('#loading').remove();
			let place = 0;
			for (row of result.hunterSnapshotRows) {

				if ($('#' + row.group).length) {
					$('#' + row.group).find('.hunter').css('left', row.quantity / 50_000_000 * 100 + '%');
					$('#' + row.group).data('place', place);
				} else {
					$('.hunter-field').append(
						`
						<div class="hunter-lane" id="${row.group}" data-place="${place}">
							<div class="hunter" style="left: 0%">
								<span class="top">${row.handle || row.address}</span>
								<img src="/images/output-onlinegiftools.gif">
								<span class="bottom">${row.quantity}</span>
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

			$('.hunter-field').css('height', $('.hunter-lane').length * 110 + 36);
			$('.hunter-lane').each((i, el) => {
				$(el).css('top', $(el).data('place') * 110 + 36);
			});

		}
	});
}

setInterval(updateHunt, 1000);
updateHunt();