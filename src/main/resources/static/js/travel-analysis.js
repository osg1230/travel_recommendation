// 중복 클릭을 막고 처리 상태를 알린다. 서버에서도 세션별 중복 호출을 차단한다.
document.querySelector('[data-analysis-form]')?.addEventListener('submit', event => {
    const form = event.currentTarget;
    form.querySelector('button').disabled = true;
    form.setAttribute('aria-busy', 'true');
    form.querySelector('[data-analysis-status]').hidden = false;
});
window.addEventListener('pageshow', () => {
    const form = document.querySelector('[data-analysis-form]');
    if (!form) return;
    form.querySelector('button').disabled = false;
    form.removeAttribute('aria-busy');
    form.querySelector('[data-analysis-status]').hidden = true;
});
