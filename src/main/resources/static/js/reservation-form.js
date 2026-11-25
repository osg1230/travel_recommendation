// 예약·결제 화면: 고른 객실의 1실 최대 인원으로 인원 입력의 최대값을 맞추고, 오른쪽 '예약·결제' 칸에 고른 내용과 금액을 채운다.
// 서버도 같은 규칙(객실당 최대 인원, 금액 = 1단위 가격 × 수량)을 다시 검사하고 계산한다.
(function () {
    const form = document.querySelector('[data-reservation-form]');
    if (!form) return;
    const quantity = form.querySelector('input[name=quantity]');
    const party = form.querySelector('input[name=partySize]');
    const guide = form.querySelector('[data-party-guide]');
    const preview = form.querySelector('[data-price-preview]');   // 계산식: 120,000원 × 1실
    const choice = form.querySelector('[data-sum-choice]');       // 고른 내용: 스탠다드 더블 1실 · 2명
    const total = form.querySelector('[data-sum-total]');         // 합계 금액
    const button = form.querySelector('[data-pay-button]');
    const rows = form.querySelectorAll('[data-sum-row]');
    const won = n => n.toLocaleString('ko-KR') + '원';
    const show = visible => rows.forEach(row => { row.hidden = !visible; });

    const update = () => {
        const people = Math.max(1, Number(party.value) || 1);
        let amount;
        if (quantity) {
            // 숙소: 고른 객실 종류의 가격 × 객실 수, 인원은 1실 최대 인원 × 객실 수까지
            const room = form.querySelector('input[name=stockId]:checked');
            if (!room) { show(false); return; }
            const rooms = Math.max(1, Number(quantity.value) || 1);
            const perRoom = Number(room.dataset.maxPeople);
            const price = Number(room.dataset.price);
            party.max = rooms * perRoom;
            guide.textContent = '고른 객실은 1실에 최대 ' + perRoom + '명이라, 지금 객실 수로는 최대 ' + party.max
                + '명까지 예약할 수 있어요. 인원이 더 많으면 객실 수를 늘려 주세요.';
            amount = price * rooms;
            choice.textContent = room.dataset.name + ' ' + rooms + '실 · ' + people + '명';
            preview.textContent = won(price) + ' × ' + rooms + '실';
        } else {
            // 관광시설: 1명 가격 × 인원
            const price = Number(form.dataset.unitPrice);
            amount = price * people;
            choice.textContent = people + '명';
            preview.textContent = won(price) + ' × ' + people + '명';
        }
        total.textContent = won(amount);
        // 버튼에도 금액을 적어 얼마를 결제하는지 누르기 전에 알게 한다. 무료 시설은 '예약하기' 그대로 둔다.
        if (button.dataset.free !== 'true') button.textContent = won(amount) + ' 결제하고 예약하기';
        show(true);
    };
    form.addEventListener('input', update);
    form.addEventListener('change', update);
    update();
})();
