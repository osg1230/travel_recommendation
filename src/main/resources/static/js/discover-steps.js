// 취향 탐색 질문을 한 장씩 보여 준다. 장면(또는 상관없음·건너뛰기)을 고르면 잠시 뒤 다음 질문으로 넘어가고,
// 마지막 질문에서만 '내 취향 확인하기' 버튼을 보여 준다. 스크립트가 없으면 모든 질문이 한 화면에 그대로 보인다.
(function () {
    const form = document.querySelector('[data-discover-form]');
    if (!form) return;
    const steps = Array.from(form.querySelectorAll('[data-discover-step]'));
    const progress = form.querySelector('[data-discover-progress]');
    const nav = form.querySelector('[data-discover-nav]');
    const prev = form.querySelector('[data-discover-prev]');
    const next = form.querySelector('[data-discover-next]');
    const submit = form.querySelector('[data-discover-submit] button');
    let current = 0;
    let timer = null;

    function show(index) {
        clearTimeout(timer);
        current = index;
        steps.forEach((step, i) => { step.hidden = i !== index; });
        const last = index === steps.length - 1;
        progress.textContent = (index + 1) + ' / ' + steps.length;
        prev.hidden = index === 0;
        next.hidden = last;
        submit.hidden = !last;
    }

    // 고른 뒤 바로 넘기면 무엇을 골랐는지 볼 틈이 없어 잠깐 기다린다. 마지막 질문은 넘기지 않는다.
    steps.forEach((step, i) => step.addEventListener('change', () => {
        clearTimeout(timer);
        if (i < steps.length - 1) timer = setTimeout(() => show(i + 1), 350);
    }));
    prev.addEventListener('click', () => show(current - 1));
    next.addEventListener('click', () => show(current + 1));

    progress.hidden = false;
    nav.hidden = false;
    form.classList.add('discover-stepped');
    show(0);
})();
