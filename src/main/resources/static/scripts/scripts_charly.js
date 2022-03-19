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
});
