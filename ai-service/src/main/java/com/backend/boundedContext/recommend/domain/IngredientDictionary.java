package com.backend.boundedContext.recommend.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static com.backend.boundedContext.recommend.domain.IngredientTag.*;

/**
 * 판매자가 붙여 넣은 전성분을 표준 성분명과 대조해 성분 태그를 찾는다.
 * LLM으로 추출하지 않는다: 빠뜨리면 피하는 성분이 든 상품이 추천되므로 같은 입력에 항상 같은 결과가 나와야 한다.
 * 화장품 전성분은 표준 성분명(대한화장품협회 성분사전)으로 적게 되어 있어 이름 대조로 충분하다.
 */
public final class IngredientDictionary {

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private enum Kind { EXACT, PREFIX, SUFFIX, CONTAINS }

    private record Rule(IngredientTag tag, Kind kind, String term) {
        boolean matches(String name) {
            return switch (kind) {
                case EXACT -> name.equals(term);
                case PREFIX -> name.startsWith(term);
                case SUFFIX -> name.endsWith(term);
                case CONTAINS -> name.contains(term);
            };
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();

    // 성분명은 대한화장품협회 성분사전(kcia.or.kr/cid)의 표준 성분명과 구명칭으로 확인
    // 구명칭(예: 디메치콘, 메칠파라벤)은 예전 포장에 그대로 적혀 있을 수 있어 같이 둔다
    static {
        add(ALCOHOL, Kind.EXACT, "에탄올", "변성알코올", "알코올");   // 알코올: 표준명은 아니지만 판매자가 그렇게 적는 경우가 있어 둔다
        add(PARABEN, Kind.SUFFIX, "파라벤");   // 메틸파라벤, 메칠파라벤(구명칭), 프로필파라벤…
        // 메티콘(구명칭 메치콘)·실록세인(구명칭 실록산)이 들어간 이름은 모두 실리콘이다
        // (다이메티콘, 아모다이메티콘, 페닐트라이메티콘, 다이메티콘크로스폴리머, 사이클로펜타실록세인…)
        add(SILICONE, Kind.CONTAINS, "메티콘", "메치콘", "실록세인", "실록산");
        add(FRAGRANCE, Kind.EXACT, "향료", "fragrance", "parfum");   // 영문은 수입 화장품 표기
        // 알레르기 유발 착향제 25종(식약처 고시). 일정 함량을 넘으면 "향료"와 따로 적어야 한다. 괄호 안은 구명칭
        add(FRAGRANCE, Kind.EXACT,
                "아밀신남알", "벤질알코올", "신나밀알코올", "시트랄", "유제놀", "하이드록시시트로넬알",
                "아이소유제놀", "이소유제놀", "아밀신나밀알코올", "벤질살리실레이트", "신남알", "쿠마린",
                "제라니올", "아니스알코올", "아니스에탄올", "벤질신나메이트", "파네솔",
                "부틸페닐메틸프로피오날", "부틸페닐메칠프로피오날", "리날룰", "벤질벤조에이트",
                "시트로넬올", "시트로넬롤", "헥실신남알", "헥실신나몰", "리모넨",
                "메틸2-옥티노에이트", "메칠2-옥티노에이트", "알파-아이소메틸아이오논", "알파-이소메칠이오논",
                "참나무이끼추출물", "나무이끼추출물");
        add(ESSENTIAL_OIL, Kind.EXACT,
                "라벤더오일", "티트리잎오일", "페퍼민트오일", "유칼립투스잎오일", "로즈마리잎오일",
                "레몬껍질오일", "오렌지껍질오일", "베르가모트오일", "베르가모트열매오일", "일랑일랑꽃오일",
                "센티드제라늄오일", "센티드제라늄꽃오일", "와일드제라늄오일", "제라늄오일");
        add(MINERAL_OIL, Kind.EXACT, "미네랄오일");
        add(NIACINAMIDE, Kind.EXACT, "나이아신아마이드");
        add(CICA, Kind.EXACT, "병풀추출물", "병풀잎추출물", "병풀잎수", "마데카소사이드", "아시아티코사이드",
                "마데카식애씨드", "아시아틱애씨드");
        add(VITAMIN_C, Kind.EXACT, "아스코빅애씨드", "에칠아스코빌에텔", "3-o-에틸아스코빅애씨드",
                "아스코빌글루코사이드", "소듐아스코빌포스페이트", "마그네슘아스코빌포스페이트");
        add(HYALURONIC_ACID, Kind.EXACT, "하이알루로닉애씨드", "소듐하이알루로네이트", "하이드롤라이즈드하이알루로닉애씨드");
        add(CERAMIDE, Kind.PREFIX, "세라마이드");   // 세라마이드엔피(구명칭 세라마이드3)…
        add(RETINOL, Kind.EXACT, "레티놀", "레틴알", "레티날", "레티닐팔미테이트", "하이드록시피나콜론레티노에이트");
        add(PANTHENOL, Kind.EXACT, "판테놀", "덱스판테놀");
        add(TEA_TREE, Kind.EXACT, "티트리잎오일", "티트리잎추출물", "티트리잎수");
        add(PEPTIDE, Kind.CONTAINS, "펩타이드");
        // 시트릭애씨드는 pH 조절제로 흔히 들어가 AHA로 치지 않는다
        add(AHA_BHA, Kind.EXACT, "글라이콜릭애씨드", "락틱애씨드", "만델릭애씨드", "살리실릭애씨드", "베타인살리실레이트");
    }

    private static void add(IngredientTag tag, Kind kind, String... terms) {
        for (String term : terms) RULES.add(new Rule(tag, kind, normalize(term)));
    }

    private IngredientDictionary() {
    }

    /**
     * @param tags           찾은 태그
     * @param unmatchedNames 어떤 태그에도 걸리지 않은 성분명(정규화한 이름). 정제수·글리세린처럼 정상인 것이 대부분이고, 사전 보강에 쓴다
     * @param known          성분명이 하나라도 있으면 true. 전성분이 없으면 피하는 성분이 들었는지 알 수 없다
     */
    public record IngredientMatch(Set<IngredientTag> tags, List<String> unmatchedNames, boolean known) {
    }

    public static IngredientMatch match(String ingredientsText) {
        Set<IngredientTag> tags = EnumSet.noneOf(IngredientTag.class);
        List<String> unmatched = new ArrayList<>();
        if (ingredientsText == null) return new IngredientMatch(tags, unmatched, false);

        boolean known = false;
        for (String piece : split(ingredientsText)) {
            Parts parts = parse(piece);
            // 괄호 밖 이름: 미매칭 목록과 known은 이것만 본다(괄호 안 영문명 "정제수(Water)"의 water가 사전 보강 목록에 섞이지 않게)
            String name = clean(parts.outside());
            boolean matched = tag(name, tags);
            // 괄호 안 성분명도 태그에는 쓴다: "향료(리모넨, 리날룰)"의 리모넨·리날룰도 착향제다
            for (String inner : innerNames(parts.groups())) tag(inner, tags);

            if (name.isEmpty()) continue;
            known = true;
            if (!matched) unmatched.add(name);
        }
        return new IngredientMatch(tags, unmatched, known);
    }

    // 이름에 맞는 규칙의 태그를 모두 더한다. 하나라도 맞으면 true
    private static boolean tag(String name, Set<IngredientTag> tags) {
        if (name.isEmpty()) return false;
        boolean matched = false;
        for (Rule rule : RULES) {
            if (rule.matches(name)) {
                tags.add(rule.tag());
                matched = true;
            }
        }
        return matched;
    }

    /**
     * 괄호 밖의 구분자에서만 나눈다: "향료(리모넨, 리날룰)"은 한 조각.
     * 구분자는 쉼표(앞뒤가 모두 숫자면 나누지 않음: "1,2-헥산다이올")·전각 쉼표·모점·줄바꿈.
     * 닫는 괄호가 없으면 끝까지 괄호 안으로 보고, 여는 괄호 없이 닫는 괄호만 있으면 무시한다(깊이는 0 아래로 내려가지 않는다).
     */
    static List<String> split(String text) {
        List<String> pieces = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isOpen(c)) depth++;
            else if (isClose(c)) depth = Math.max(0, depth - 1);
            else if (depth == 0 && isSeparator(text, i)) {
                pieces.add(text.substring(start, i));
                start = i + 1;
            }
        }
        pieces.add(text.substring(start));
        return pieces;
    }

    private static boolean isSeparator(String text, int i) {
        char c = text.charAt(i);
        if (c == ',') {
            boolean digitBefore = i > 0 && Character.isDigit(text.charAt(i - 1));
            boolean digitAfter = i + 1 < text.length() && Character.isDigit(text.charAt(i + 1));
            return !(digitBefore && digitAfter);
        }
        return c == '，' || c == '、' || c == '\n' || c == '\r';
    }

    private static boolean isOpen(char c) {
        return c == '(' || c == '（';
    }

    private static boolean isClose(char c) {
        return c == ')' || c == '）';
    }

    // 괄호 안 내용들을 같은 규칙으로 나눈 이름들(정규화). 괄호가 겹쳐 있으면 안쪽까지 모두.
    // 재귀 대신 할 일 목록으로 돈다: 괄호가 수천 겹이어도 스택이 넘치지 않게(StackOverflowError)
    private static List<String> innerNames(List<String> groups) {
        List<String> names = new ArrayList<>();
        Deque<String> pending = new ArrayDeque<>(groups);
        while (!pending.isEmpty()) {
            for (String inner : split(pending.poll())) {
                Parts parts = parse(inner);
                String name = clean(parts.outside());
                if (!name.isEmpty()) names.add(name);
                pending.addAll(parts.groups());
            }
        }
        return names;
    }

    // outside: 괄호 밖 글자, groups: 깊이 0에서 연 괄호 안의 내용들
    private record Parts(String outside, List<String> groups) {
    }

    // 조각을 한 번 훑어 괄호 밖 글자와 괄호 안 내용을 나눈다.
    // 짝이 안 맞는 괄호는 split과 같은 규칙(닫히지 않으면 끝까지 괄호 안, 짝 없는 닫는 괄호는 버림)
    private static Parts parse(String piece) {
        StringBuilder outside = new StringBuilder();
        List<String> groups = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < piece.length(); i++) {
            char c = piece.charAt(i);
            if (isOpen(c)) {
                if (depth == 0) start = i + 1;
                depth++;
            } else if (isClose(c)) {
                if (depth > 0 && --depth == 0) groups.add(piece.substring(start, i));
            } else if (depth == 0) {
                outside.append(c);
            }
        }
        if (depth > 0) groups.add(piece.substring(start));
        return new Parts(outside.toString(), groups);
    }

    // 괄호 밖 이름: 괄호와 그 안(영문명 등)을 지우고, 모든 공백을 지우고 영문은 소문자로
    static String normalize(String name) {
        return clean(parse(name).outside());
    }

    private static String clean(String outside) {
        return SPACES.matcher(outside).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
