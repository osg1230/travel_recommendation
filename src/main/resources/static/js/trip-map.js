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

    // 사용자에게는 할 수 있는 일만 알리고, 개발자가 볼 원인은 브라우저 콘솔에 남긴다.
    function mapFailed() {
        tell('지도를 불러오지 못했어요. 인터넷 연결을 확인하고 새로고침해 주세요. 아래 목록과 링크는 그대로 쓸 수 있어요.');
        console.warn('카카오 지도 SDK를 불러오지 못했습니다. 네트워크, JavaScript 키, 카카오 개발자 콘솔의 웹 도메인 등록을 확인하세요.');
    }
    const failed = setTimeout(mapFailed, 8000);
    const script = document.createElement('script');
    script.src = 'https://dapi.kakao.com/v2/maps/sdk.js?autoload=false&libraries=services&appkey=' + encodeURIComponent(key);
    // 등록하지 않은 사이트 도메인(401)도 네트워크 오류처럼 onerror로만 알려진다.
    script.onerror = () => { clearTimeout(failed); mapFailed(); };
    script.onload = () => window.kakao.maps.load(() => { clearTimeout(failed); canvas.hidden = false; draw(); });
    document.head.appendChild(script);

    // 일정 항목과 이어진 번호는 눌러서 목록으로 갈 수 있게 버튼으로 만든다.
    function badge(text, color, title, dashed, clickable) {
        const el = document.createElement(clickable ? 'button' : 'span');
        el.className = 'map-badge' + (dashed ? ' map-badge-approx' : '');
        el.style.setProperty('--badge', color);
        el.textContent = text;
        el.title = title;
        if (clickable) el.type = 'button';
        else el.setAttribute('role', 'img');
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
            const overlay = new maps.CustomOverlay({ map, position: at, content: element, yAnchor: 0.5 });
            bounds.extend(at); count++;
            return { at, overlay };
        };
        const fit = () => { if (count > 1) map.setBounds(bounds); else if (count === 1) map.setCenter(bounds.getSouthWest()); };

        if (Array.isArray(data.days)) {
            drawDays(map, place, fit);
            // 도로 경로를 받은 구간이 하나라도 있으면 선의 의미가 달라지므로 안내 문장을 바꾼다.
            const hasRoads = data.days.some((day) => (day.legs || []).some((leg) => leg.length > 0));
            const lineNote = hasRoads
                ? '선은 조회한 자동차 추천 경로를 따라 그렸고, 경로를 받지 못한 구간은 직선으로 이었어요. 실제 주행 경로는 달라질 수 있어요.'
                : '선은 방문 순서를 이은 것이며 실제 이동 경로가 아니에요.'
                    + (box.dataset.dayPaths ? ' 아래 버튼을 누르면 그날의 실제 대중교통·도보 경로를 지금 찾아 그려요.' : '');
            tell(count ? '번호는 일차-방문 순서예요. 번호를 누르면 일정 항목으로 가요. ' + lineNote : '지도에 표시할 좌표가 있는 장소가 없어요.');
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

    // 일정 지도: 일차별 색으로 번호를 붙이고 방문 순서대로 선을 잇는다. 자동차 일정 초안은 서버가 준 도로 경로를 따라 그리고, 그 밖에는 직선이다.
    // 지도 번호를 누르면 아래 일정 항목으로 가고, 일정 항목의 '지도에서 보기'를 누르면 지도가 그 장소로 간다.
    function drawDays(map, place, fitAll) {
        const maps = window.kakao.maps;
        const days = [];   // 일차별 { number, marks: [{ at, overlay }], line }
        const stops = {};  // "일차-항목순번" → { at, element }
        data.days.forEach((day, index) => {
            const color = COLORS[index % COLORS.length];
            const marks = day.stops.map((stop) => {
                const key = day.number + '-' + stop.item;
                const item = document.querySelector('[data-map-stop="' + key + '"]');
                const title = day.number + '일차 ' + stop.order + '번째 · ' + (TYPE_NAMES[stop.type] || '') + ' · ' + stop.name;
                const element = badge(day.number + '-' + stop.order, color, item ? title + ' (눌러서 일정에서 보기)' : title, false, !!item);
                const mark = place(stop.lat, stop.lng, element);
                if (item) {
                    stops[key] = { at: mark.at, element };
                    element.addEventListener('click', () => showItem(item));
                    addMapButton(item, key);
                }
                return mark;
            });
            // 구간마다 서버가 준 도로 경로(legs)가 있으면 그 좌표를 따라 그리고, 없으면 두 장소를 직선으로 잇는다.
            const path = [];
            marks.forEach((mark, i) => {
                path.push(mark.at);
                const leg = (day.legs || [])[i];
                if (leg && i < marks.length - 1) leg.forEach(([lat, lng]) => path.push(new maps.LatLng(lat, lng)));
            });
            const line = marks.length > 1
                ? new maps.Polyline({ map, path, strokeWeight: 4, strokeColor: color, strokeOpacity: 0.75, strokeStyle: 'solid' })
                : null;
            // live는 '실제 경로 보기'로 그린 선들이다. 그린 날은 직선(line) 대신 이 선을 보여 준다.
            days.push({ number: day.number, marks, line, color, live: null });
        });
        fitAll();
        followDayTabs(days, map, fitAll, setupDayPaths(days, map));

        function addMapButton(item, key) {
            const links = item.querySelector('.place-links');
            if (!links) return;
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'map-jump';
            button.textContent = '지도에서 보기';
            button.addEventListener('click', () => focusStop(key));
            links.prepend(button);
        }

        // 그 장소로 지도를 옮기고 번호를 강조한다. 버튼은 지금 보이는 날의 항목에만 있어 그 번호도 지도에 보인다.
        function focusStop(key) {
            const stop = stops[key];
            map.setLevel(Math.min(map.getLevel(), 5));
            map.panTo(stop.at);
            document.querySelectorAll('.map-badge-active').forEach((el) => el.classList.remove('map-badge-active'));
            stop.element.classList.add('map-badge-active');
            // 지도가 이미 화면에 보이면(넓은 화면의 고정 지도) 화면을 움직이지 않는다.
            box.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
    }

    // 대중교통 일정: 고른 날의 실제 대중교통·도보 경로를 버튼으로 조회해 그린다(SFR-11-03).
    // 카카오 정책상 서버는 경로를 저장하지 않고 누를 때마다 새로 조회한다. 타는 구간은 실선, 걷는 구간은 점선이다.
    // 고른 날이 바뀔 때 버튼 글자를 맞추는 함수를 돌려준다. 대중교통 일정이 아니면 null이다.
    function setupDayPaths(days, map) {
        const url = box.dataset.dayPaths;
        if (!url || !days.length || !status) return null;
        const maps = window.kakao.maps;
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'button-quiet button-small trip-map-paths';
        status.after(button);
        let current = days.length === 1 ? days[0].number : 1;
        const update = () => {
            const day = days.find((d) => d.number === current);
            if (!day) { button.disabled = true; button.textContent = '일차를 고르면 그날 실제 경로를 볼 수 있어요'; return; }
            button.disabled = !!day.live || day.marks.length < 2;
            button.textContent = day.live ? current + '일차 실제 경로를 표시했어요' : current + '일차 실제 경로 보기';
        };
        const drawLive = (day, body) => {
            const lines = [];
            (body.legs || []).forEach((pieces, i) => {
                // 경로를 찾지 못한 구간은 두 장소를 직선으로 남긴다.
                if (!pieces.length) {
                    if (day.marks[i + 1]) lines.push(new maps.Polyline({ map, path: [day.marks[i].at, day.marks[i + 1].at],
                        strokeWeight: 3, strokeColor: day.color, strokeOpacity: 0.5, strokeStyle: 'solid' }));
                    return;
                }
                pieces.forEach((piece) => {
                    const walk = piece.mode === 'WALK';
                    lines.push(new maps.Polyline({ map, path: piece.points.map(([lat, lng]) => new maps.LatLng(lat, lng)),
                        strokeWeight: walk ? 3 : 5, strokeColor: day.color, strokeOpacity: walk ? 0.95 : 0.8,
                        strokeStyle: walk ? 'shortdash' : 'solid' }));
                });
            });
            if (day.line) day.line.setMap(null);
            day.live = lines;
        };
        button.addEventListener('click', async () => {
            const day = days.find((d) => d.number === current);
            if (!day || day.live) return;
            button.disabled = true;
            button.textContent = '경로를 찾고 있어요…';
            try {
                const response = await fetch(url + '?day=' + encodeURIComponent(day.number), { headers: { Accept: 'application/json' } });
                const body = await response.json().catch(() => ({}));
                if (!response.ok) throw new Error(body.message || '');
                drawLive(day, body);
                tell(day.number + '일차 선은 방금 조회한 카카오 대중교통·도보 경로예요(실선: 버스·지하철, 점선: 걷기). 실제 운행과 길 상황에 따라 달라질 수 있어요.'
                    + (body.failed ? ' 경로를 찾지 못한 ' + body.failed + '구간은 직선으로 남겼어요.' : ''));
            } catch (error) {
                tell('실제 경로를 불러오지 못했어요. ' + (error.message || '잠시 후 다시 시도해 주세요.'));
            }
            update();
        });
        update();
        return (number) => { current = number; update(); };
    }

    // 화면 위 일차 버튼(day-tabs.js)을 따라 그날 번호와 선만 보이고 그날 범위로 지도를 맞춘다.
    function followDayTabs(days, map, fitAll, onDay) {
        const maps = window.kakao.maps;
        const show = (number) => {
            days.forEach((day) => {
                const shown = number === 0 || day.number === number;
                day.marks.forEach((mark) => mark.overlay.setMap(shown ? map : null));
                if (day.line) day.line.setMap(shown && !day.live ? map : null);
                (day.live || []).forEach((line) => line.setMap(shown ? map : null));
            });
            if (onDay) onDay(number);
            if (number === 0) { fitAll(); return; }
            const day = days.find((d) => d.number === number);
            if (!day || !day.marks.length) return;
            const bounds = new maps.LatLngBounds();
            day.marks.forEach((mark) => bounds.extend(mark.at));
            if (day.marks.length > 1) map.setBounds(bounds); else map.setCenter(day.marks[0].at);
        };
        document.addEventListener('tripday', (event) => show(event.detail));
        // 지도가 버튼보다 늦게 준비되므로 이미 고른 날이 있으면 그날로 맞춘다.
        const selected = document.querySelector('[data-day-tabs]')?.dataset.selected;
        if (selected) show(Number(selected));
    }

    // 목록의 해당 일정 항목으로 이동해 잠깐 눈에 띄게 표시한다(다시 눌러도 표시되게 클래스를 다시 붙인다).
    function showItem(item) {
        item.scrollIntoView({ behavior: 'smooth', block: 'center' });
        item.classList.remove('schedule-item-focus');
        void item.offsetWidth;
        item.classList.add('schedule-item-focus');
    }
})();
