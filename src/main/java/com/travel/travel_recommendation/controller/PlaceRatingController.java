package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.PlaceRatingView;
import com.travel.travel_recommendation.service.PlaceRatingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 장소 평가 화면(SFR-10-01·03·04)과 내 평가 목록. 회원만 이용한다. */
@Controller
@RequiredArgsConstructor
@RequestMapping("/my/ratings")
public class PlaceRatingController {
    private final ObjectProvider<PlaceRatingService> services;
    private final ObjectProvider<com.travel.travel_recommendation.service.PlacePhotoService> photos;

    @GetMapping
    String list(Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("ratings",service().mine(principal.getName()));
        return "my/ratings";
    }

    /** 다른 회원이 남긴 평가를 장소별로 모아 보는 화면. 장소 이름 일부(q)로 좁힐 수 있다. */
    @GetMapping("/browse")
    String browse(@RequestParam(required=false) String q, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        String keyword=q==null?"":q.strip();
        if(keyword.length()>50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"검색어는 50자까지 적을 수 있어요.");
        model.addAttribute("keyword",keyword);
        model.addAttribute("places",service().browse(keyword));
        model.addAttribute("browseLimit",PlaceRatingService.BROWSE_LIMIT);
        return "my/ratings-browse";
    }

    @GetMapping("/{placeId}")
    String form(@PathVariable long placeId, @RequestParam(required=false) String returnTo, Principal principal,
                HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        var form=service().form(placeId,principal.getName());
        model.addAttribute("form",form);
        // 저장에 실패해 돌아왔으면 flash로 받은 방금 적은 값(draft)을, 아니면 내 기존 평가를 입력칸에 채운다.
        if(!model.containsAttribute("draft") && form.mine()!=null)
            model.addAttribute("draft",new PlaceRatingView.Draft(form.mine().score(),form.mine().tagIds(),form.mine().comment()));
        // 저장해 둔 관광공사 사진이 있으면 장소 카드에 보여 준다. 없거나 조회하지 못하면 사진 없이 보여 준다.
        Object placePhoto=null;
        var photo=photos.getIfAvailable();
        try { if(photo!=null) placePhoto=photo.photos(List.of(placeId)).get(placeId); } catch(RuntimeException error) { placePhoto=null; }
        model.addAttribute("placePhoto",placePhoto);
        model.addAttribute("maxTags",PlaceRatingService.MAX_TAGS);
        model.addAttribute("maxCommentLength",PlaceRatingService.MAX_COMMENT_LENGTH);
        model.addAttribute("returnTo",safe(returnTo));
        return "my/rating";
    }

    @PostMapping("/{placeId}")
    String save(@PathVariable long placeId, @RequestParam(required=false) Integer score, @RequestParam(required=false) List<Long> tags,
                @RequestParam(required=false) String comment, @RequestParam(required=false) String returnTo,
                Principal principal, RedirectAttributes redirect) {
        boolean created;
        try {
            created=service().save(placeId,principal.getName(),score,tags,comment);
        } catch(ResponseStatusException | DataAccessException | TransactionException failed) {
            // 장소를 찾지 못했거나(404) 평가 기능을 쓸 수 없으면(503) 오류 화면으로 보낸다.
            if(failed instanceof ResponseStatusException e && (e.getStatusCode().value()==404 || e.getStatusCode().value()==503)) throw failed;
            // 그 밖의 실패(입력 오류, 다른 화면에서 먼저 평가, 일시적인 DB 오류)는 적은 별점·태그·한줄평을 다시 채운 평가 화면으로 돌아가 안내한다.
            redirect.addFlashAttribute("ratingError",failed instanceof ResponseStatusException e ? e.getReason()
                : "평가를 저장하지 못했어요. 적은 내용을 다시 채워 두었으니 잠시 후 다시 저장해 주세요.");
            redirect.addFlashAttribute("draft",new PlaceRatingView.Draft(score,tags,comment));
            if(safe(returnTo)!=null) redirect.addAttribute("returnTo",safe(returnTo));
            return "redirect:/my/ratings/"+placeId;
        }
        String name=service().placeName(placeId);
        redirect.addFlashAttribute("savedMessage","‘"+name+"’ "+(created?"평가를 등록했어요.":"평가를 새로 고른 내용으로 바꿨어요."));
        return back(returnTo,"/my/ratings/"+placeId,redirect);
    }

    @PostMapping("/{placeId}/delete")
    String delete(@PathVariable long placeId, @RequestParam(required=false) String returnTo, Principal principal, RedirectAttributes redirect) {
        String name=service().placeName(placeId);
        boolean deleted=service().delete(placeId,principal.getName());
        // 두 번 누르거나 다른 탭에서 먼저 지웠으면 이미 원하는 결과라 오류 대신 그렇게 안내한다.
        redirect.addFlashAttribute("savedMessage",deleted?"‘"+name+"’ 평가를 삭제했어요.":"‘"+name+"’ 평가는 이미 삭제됐어요.");
        return back(returnTo,"/my/ratings",redirect);
    }

    @ExceptionHandler({ResponseStatusException.class,DataAccessException.class,TransactionException.class})
    String error(RuntimeException error, HttpServletRequest request, HttpServletResponse response, Model model) {
        int status; String message;
        if(error instanceof ResponseStatusException e) { status=e.getStatusCode().value(); message=e.getReason(); }
        else { status=503; message="평가를 처리하지 못했어요. 잠시 후 다시 시도해 주세요."; }
        response.setStatus(status); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",message);
        // 평가 저장·삭제에 실패하면 그 장소의 평가 화면으로 돌아갈 링크를 둔다. 장소가 없는 404는 다시 열어도 같아 두지 않는다.
        var action=RATING_ACTION.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        if("POST".equals(request.getMethod()) && status!=404 && action.matches()) {
            model.addAttribute("backPlaceId",Long.valueOf(action.group(1)));
            model.addAttribute("backReturnTo",safe(request.getParameter("returnTo")));
        }
        return "my/rating-error";
    }
    private static final Pattern RATING_ACTION=Pattern.compile("^/my/ratings/(\\d+)(/delete)?$");

    // 돌아갈 주소는 이 서비스의 정해진 화면만 허용해 외부로 이동하지 않는다.
    // 내 평가·즐겨찾기 목록, 평가 둘러보기(검색어 q 포함), 저장 일정 상세(#stop-일차-순번), 장소 후보 화면(#place-장소번호)만 받는다.
    private static final Pattern RETURN_TO=Pattern.compile("^(/my/ratings|/my/favorites"
        + "|/my/ratings/browse(\\?q=[^#&\\s]{1,500})?"
        + "|/my/itineraries/\\d+(#stop-\\d+-\\d+)?"
        + "|/travel/recommendations/[0-9a-fA-F-]{36}/destinations/\\d+/places(#place-\\d+)?)$");
    private static String safe(String returnTo) {
        return returnTo!=null && RETURN_TO.matcher(returnTo).matches() ? returnTo : null;
    }
    /**
     * 저장·삭제 뒤 보던 화면으로 돌아간다. 주소에 항목 위치(#…)가 있으면 그 항목 옆에 안내를 보여 주도록 위치 이름을 함께 넘긴다.
     */
    private static String back(String returnTo, String fallback, RedirectAttributes redirect) {
        String target=safe(returnTo);
        if(target==null) return "redirect:"+fallback;
        if(target.contains("#")) redirect.addFlashAttribute("ratingAnchor",target.substring(target.indexOf('#')+1));
        return "redirect:"+target;
    }
    private PlaceRatingService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"장소 평가는 DB 연결 후 이용할 수 있어요.");
        return service;
    }
}
