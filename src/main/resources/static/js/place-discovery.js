document.querySelector('[data-place-discovery]')?.addEventListener('submit', event => {
    const form = event.currentTarget;
    form.querySelector('button').disabled = true;
    form.querySelector('[data-lookup-status]').textContent = '카카오에서 장소를 확인하고 있어요. 잠시 기다려 주세요.';
});
// 뒤로가기로 돌아와도 조회 버튼을 다시 사용할 수 있다.
window.addEventListener('pageshow', () => {
    const form = document.querySelector('[data-place-discovery]');
    if (form) form.querySelector('button').disabled = false;
});
