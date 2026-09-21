// 태그를 최대 개수까지 고르면 나머지를 잠시 막는다. 서버에서도 같은 제한을 다시 검사한다.
(function () {
    const box = document.querySelector('[data-max-tags]');
    if (!box) return;
    const max = Number(box.dataset.maxTags);
    const inputs = [...box.querySelectorAll('input[type=checkbox]')];
    const update = () => {
        const full = inputs.filter(i => i.checked).length >= max;
        inputs.forEach(i => { i.disabled = full && !i.checked; });
    };
    inputs.forEach(i => i.addEventListener('change', update));
    update();
})();
