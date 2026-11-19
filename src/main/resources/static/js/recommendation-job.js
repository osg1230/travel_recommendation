// 추천 요청 폼(후보 찾기·추천 받기·다시 찾기·AI 이유 없이 계속하기)은 한 화면에 여러 개일 수 있어 모두 연결한다.
document.querySelectorAll('[data-recommendation-form]').forEach(form => form.addEventListener('submit', async event => {
    event.preventDefault();
    const button = form.querySelector('button');
    const status = form.querySelector('[data-submit-status]');
    button.disabled = true;
    status.textContent = '요청을 접수하고 있어요.';
    try {
        const response = await fetch(form.action, { method: 'POST', body: new FormData(form), headers: { Accept: 'application/json' } });
        if (!response.ok) {
            // 서버가 보낸 이유(예: 요청이 많아 잠시 뒤 다시 시도, 다시 시도할 수 없는 요청)를 그대로 보여 준다.
            let message = '';
            try { message = (await response.json()).message || ''; } catch { message = ''; }
            status.textContent = message || (response.status === 409 ? '조건이 바뀌었거나 만료됐어요. 조건을 다시 확정해 주세요.' :
                response.status === 403 || response.status === 404 ? '요청이 만료되었거나 조회 권한이 없어요. 조건을 다시 입력해 주세요.' :
                '요청을 접수하지 못했어요. 잠시 후 다시 시도해 주세요.');
            button.disabled = false;
            return;
        }
        const result = await response.json();
        // 접수에 성공하면 결과 화면으로 넘어가므로 버튼을 다시 켜지 않는다(넘어가기 전 다시 눌러 같은 요청을 또 보내지 않게).
        window.location.assign(result.location);
        return;
    } catch { status.textContent = '연결이 끊겼어요. 다시 누르면 이미 접수된 요청을 확인해요.'; }
    button.disabled = false;
}));

const job = document.querySelector('[data-job-pending="true"]');
if (job) {
    // 화면이 정한 시간(data-poll-seconds, 대기 제한 + 처리 제한)만큼 상태를 확인한다. 없으면 60초다.
    const deadline = Date.now() + (Number(job.dataset.pollSeconds) || 60) * 1000;
    const message = job.querySelector('[data-job-message]');
    const show = text => { if (message && text) message.textContent = text; };
    // 연결이 잠깐 끊겨도 바로 멈추지 않게 3번까지는 간격을 늘려(4초·6초·8초) 다시 확인한다.
    let failures = 0;
    const next = delay => {
        // 다른 탭을 보고 있으면 확인을 멈췄다가, 이 탭으로 돌아오면 바로 다시 확인한다.
        if (document.hidden) { document.addEventListener('visibilitychange', poll, { once: true }); return; }
        setTimeout(poll, delay);
    };
    const poll = async () => {
        if (document.hidden) { next(0); return; }
        try {
            const response = await fetch(job.dataset.statusUrl, { headers: { Accept: 'application/json' }, cache: 'no-store' });
            if (!response.ok) throw new Error();
            const result = await response.json();
            failures = 0;
            if (!result.pending) { window.location.reload(); return; }
            // 단계가 바뀌면(후보 찾는 중 → 비교하는 중, 앞선 요청을 기다리는 중 등) 서버의 안내로 바꾼다.
            show(result.message);
            if (Date.now() >= deadline) {
                show('처리가 지연되고 있어요. 잠시 후 상태 새로고침으로 확인해 주세요.');
                return;
            }
            next(2000);
        } catch {
            failures++;
            if (failures <= 3 && Date.now() < deadline) { next(2000 + failures * 2000); return; }
            show('상태를 확인하지 못했어요. 세션과 연결 상태를 확인하고 새로고침해 주세요.');
        }
    };
    setTimeout(poll, 2000);
}
