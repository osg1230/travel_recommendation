// 카카오 지도에 장소 위치(SFR-11-01)와 일정 방문 순서(SFR-11-03)를 그린다.
// 좌표는 서버가 확인해 넘긴 값만 쓰고, 장소 이름은 DOM 텍스트로만 넣어 스크립트로 해석되지 않게 한다.
(function () {
    const box = document.querySelector('[data-trip-map]');
    if (!box) return;
    const key = box.dataset.key;
    const status = box.querySelector('[data-map-status]');
    const canvas = box.querySelector('[data-map-canvas]');
    const tell = (text) => { if (status) status.textContent = text; };
    if (!key || !canvas) return;

    let data;
    try { data = JSON.parse(box.dataset.map || '{}'); } catch (error) { tell('지도 정보를 읽지 못했어요.'); return; }

    const COLORS = ['#0f766e', '#c2410c', '#1d4ed8', '#7c3aed', '#be123c', '#15803d', '#a16207'];
    const TYPE_NAMES = { VISIT: '관광', MEAL: '식사', STAY: '숙소', ATTRACTION: '관광지', ACCOMMODATION: '숙소' };

    const failed = setTimeout(() => tell('지도를 불러오지 못했어요. 카카오 JavaScript 키와 사이트 도메인 등록을 확인해 주세요. 아래 목록과 링크는 그대로 쓸 수 있어요.'), 8000);
    const script = document.createElement('script');
    script.src = 'https://dapi.kakao.com/v2/maps/sdk.js?autoload=false&libraries=services&appkey=' + encodeURIComponent(key);
    // 등록하지 않은 사이트 도메인(401)도 네트워크 오류처럼 onerror로만 알려져 두 원인을 함께 안내한다.
    script.onerror = () => { clearTimeout(failed); tell('지도를 불러오지 못했어요. 네트워크 연결과, 카카오 개발자 콘솔에 이 사이트 주소(예: http://localhost:8080)가 웹 도메인으로 등록됐는지 확인해 주세요. 아래 목록과 링크는 그대로 쓸 수 있어요.'); };
    script.onload = () => window.kakao.maps.load(() => { clearTimeout(failed); canvas.hidden = false; draw(); });
    document.head.appendChild(script);

    function badge(text, color, title, dashed) {
        const el = document.createElement('span');
        el.className = 'map-badge' + (dashed ? ' map-badge-approx' : '');
        el.style.setProperty('--badge', color);
        el.textContent = text;
        el.title = title;
        el.setAttribute('role', 'img');
        el.setAttribute('aria-label', title);
        return el;
    }

    function draw() {
        const maps = window.kakao.maps;
        const map = new maps.Map(canvas, { center: new maps.LatLng(36.5, 127.8), level: 8 });
        map.addControl(new maps.ZoomControl(), maps.ControlPosition.RIGHT);
        const bounds = new maps.LatLngBounds();
        let count = 0;
        const place = (lat, lng, element) => {
            const at = new maps.LatLng(lat, lng);
            new maps.CustomOverlay({ map, position: at, content: element, yAnchor: 0.5 });
            bounds.extend(at); count++;
            return at;
        };
        const fit = () => { if (count > 1) map.setBounds(bounds); else if (count === 1) map.setCenter(bounds.getSouthWest()); };

        if (Array.isArray(data.days)) {
            // 일정: 일차별 색으로 번호를 붙이고 방문 순서대로 직선을 잇는다. 선은 순서 표시이며 실제 이동 경로가 아니다.
            data.days.forEach((day, index) => {
                const color = COLORS[index % COLORS.length];
                const path = day.stops.map((stop) => place(stop.lat, stop.lng,
                    badge(day.number + '-' + stop.order, color, day.number + '일차 ' + stop.order + '번째 · ' + (TYPE_NAMES[stop.type] || '') + ' · ' + stop.name)));
                if (path.length > 1) new maps.Polyline({ map, path, strokeWeight: 4, strokeColor: color, strokeOpacity: 0.75, strokeStyle: 'solid' });
            });
            fit();
            tell(count ? '번호는 일차-방문 순서예요. 선은 방문 순서를 이은 것이며 실제 이동 경로가 아니에요.' : '지도에 표시할 좌표가 있는 장소가 없어요.');
            return;
        }

        // 장소 목록: 확인된 좌표는 실선 표시, 좌표가 없는 장소는 주소로 찾아 점선 표시로 구분한다.
        (data.pins || []).forEach((pin) => place(pin.lat, pin.lng,
            badge(pin.type === 'ACCOMMODATION' ? '숙' : '관', pin.type === 'ACCOMMODATION' ? COLORS[3] : COLORS[0], TYPE_NAMES[pin.type] + ' · ' + pin.name)));
        const pending = data.pending || [];
        if (!pending.length) { fit(); tell(count ? '표시한 위치는 확인된 좌표예요.' : '지도에 표시할 장소가 없어요.'); return; }
        const geocoder = new maps.services.Geocoder();
        let left = pending.length, found = 0;
        pending.forEach((item) => geocoder.addressSearch(item.address, (result, state) => {
            if (state === maps.services.Status.OK && result.length) {
                found++;
                place(Number(result[0].y), Number(result[0].x), badge(item.type === 'ACCOMMODATION' ? '숙' : '관',
                    item.type === 'ACCOMMODATION' ? COLORS[3] : COLORS[0], TYPE_NAMES[item.type] + ' · ' + item.name + ' (주소로 찾은 위치)', true));
            }
            if (--left === 0) {
                fit();
                const missing = pending.length - found;
                tell('실선 표시는 확인된 좌표, 점선 표시는 주소로 찾은 대략적인 위치예요.' + (missing ? ' 위치를 찾지 못한 장소 ' + missing + '곳은 목록에서 확인해 주세요.' : ''));
            }
        }));
    }
})();
