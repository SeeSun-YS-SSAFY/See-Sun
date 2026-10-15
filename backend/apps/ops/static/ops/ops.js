// 시선 ops: 지우거나 막는 작업은 한 번 더 묻는다(data-confirm). 서버도 따로 확인한다.
document.addEventListener('submit', function (event) {
  var form = event.target;
  var message = form.getAttribute('data-confirm');
  if (message && !window.confirm(message)) {
    event.preventDefault();
    return;
  }
  var button = form.querySelector('button[type=submit]');
  if (button) {
    setTimeout(function () { button.disabled = true; button.textContent = '처리 중…'; }, 0);
  }
});
