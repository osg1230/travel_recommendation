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
            return;
        }
        const result = await response.json();
        window.location.assign(result.location);
    } catch { status.textContent = '연결이 끊겼어요. 다시 누르면 이미 접수된 요청을 확인해요.'; }
    finally { button.disabled = false; }
}));

const job = document.querySelector('[data-job-pending="true"]');
if (job) {
    // 화면이 정한 시간(data-poll-seconds, 대기 제한 + 처리 제한)만큼 상태를 확인한다. 없으면 60초다.
    const deadline = Date.now() + (Number(job.dataset.pollSeconds) || 60) * 1000;
    const message = job.querySelector('[data-job-message]');
    const show = text => { if (message && text) message.textContent = text; };
    const poll = async () => {
        try {
            const response = await fetch(job.dataset.statusUrl, { headers: { Accept: 'application/json' }, cache: 'no-store' });
            if (!response.ok) throw new Error();
            const result = await response.json();
            if (!result.pending) { window.location.reload(); return; }
            // 단계가 바뀌면(후보 찾는 중 → 비교하는 중, 앞선 요청을 기다리는 중 등) 서버의 안내로 바꾼다.
            show(result.message);
            if (Date.now() >= deadline) {
                show('처리가 지연되고 있어요. 잠시 후 상태 새로고침으로 확인해 주세요.');
                return;
            }
            setTimeout(poll, 2000);
        } catch { show('상태를 확인하지 못했어요. 세션과 연결 상태를 확인하고 새로고침해 주세요.'); }
    };
    setTimeout(poll, 2000);
}
