// 예약 신청 화면: 고른 객실의 1실 최대 인원으로 인원 입력의 최대값을 맞추고, 가격 × 수량 미리보기를 보여 준다.
// 서버도 같은 규칙(객실당 최대 인원, 금액 = 1단위 가격 × 수량)을 다시 검사하고 계산한다.
(function () {
    const form = document.querySelector('[data-reservation-form]');
    if (!form) return;
    const quantity = form.querySelector('input[name=quantity]');
    const party = form.querySelector('input[name=partySize]');
    const guide = form.querySelector('[data-party-guide]');
    const preview = form.querySelector('[data-price-preview]');
    const won = n => n.toLocaleString('ko-KR') + '원';

    const update = () => {
        if (quantity) {
            // 숙소: 고른 객실 종류의 가격 × 객실 수, 인원은 1실 최대 인원 × 객실 수까지
            const room = form.querySelector('input[name=stockId]:checked');
            if (!room) { preview.hidden = true; return; }
            const rooms = Math.max(1, Number(quantity.value) || 1);
            const perRoom = Number(room.dataset.maxPeople);
            const price = Number(room.dataset.price);
            party.max = rooms * perRoom;
            guide.textContent = '고른 객실은 1실에 최대 ' + perRoom + '명이라, 지금 객실 수로는 최대 ' + party.max
                + '명까지 예약할 수 있어요. 인원이 더 많으면 객실 수를 늘려 주세요.';
            preview.textContent = '예상 금액: ' + won(price) + ' × ' + rooms + '실 = ' + won(price * rooms);
        } else {
            // 관광시설: 1명 가격 × 인원
            const people = Math.max(1, Number(party.value) || 1);
            const price = Number(form.dataset.unitPrice);
            preview.textContent = '예상 금액: ' + won(price) + ' × ' + people + '명 = ' + won(price * people);
        }
        preview.hidden = false;
    };
    form.addEventListener('input', update);
    form.addEventListener('change', update);
    update();
})();
