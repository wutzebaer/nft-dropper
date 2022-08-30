$(document).ready(function() {
	const clipboard = new ClipboardJS('.btn-clipboard');
	clipboard.on('success', function(e) {
		console.log(e)
		$(e.trigger).tooltip('show');
		$(e.trigger).on('mouseout', function() {
			$(e.trigger).tooltip('dispose');
		})
		e.clearSelection();
	});

	setInterval(updateCountdown, 1000);
	updateCountdown();

});

function updateCountdown() {
	$('[data-countdown]').each((i, el) => {
		let end = Date.parse($(el).data('countdown') + 'Z');
		let now = new Date().getTime();
		let secondsLeft = Math.max((end - now) / 1000, 0);
		$(el).text(getTimeLeftString(secondsLeft));
		if (secondsLeft === 0) {
			$(el).addClass('rainbow-text')
		}
	});
}

function getTimeLeftString(timeLeft) {
	const countdown = {
		secondsToDday: Math.floor((timeLeft / 1) % 60),
		minutesToDday: Math.floor((timeLeft / (1 * 60)) % 60),
		hoursToDday: Math.floor((timeLeft / (1 * 60 * 60)) % 24),
		daysToDday: Math.floor(timeLeft / (1 * 60 * 60 * 24)),
	};
	let dayPart = '';
	if (countdown.daysToDday) {
		dayPart = `${countdown.daysToDday.toString().padStart(2, "0")} days `;
	}
	return dayPart + `${countdown.hoursToDday.toString().padStart(2, "0")}:${countdown.minutesToDday.toString().padStart(2, "0")}:${countdown.secondsToDday.toString().padStart(2, "0")}`;
}
