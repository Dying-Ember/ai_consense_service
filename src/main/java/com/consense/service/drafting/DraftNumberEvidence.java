package com.consense.service.drafting;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** Literal number representations, anchored to the field's unit/role rather than any nearby number. */
final class DraftNumberEvidence {
    private DraftNumberEvidence() { }
    private static final Map<String,Integer> WORDS=new LinkedHashMap<>();
    static {
        String[] units={"zero","one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen"};
        String[] ordinals={"zeroth","first","second","third","fourth","fifth","sixth","seventh","eighth","ninth","tenth","eleventh","twelfth","thirteenth","fourteenth","fifteenth","sixteenth","seventeenth","eighteenth","nineteenth"};
        for(int i=0;i<units.length;i++){WORDS.put(units[i],i);WORDS.put(ordinals[i],i);}
        String[] tens={"twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety"};
        String[] tensOrdinals={"twentieth","thirtieth","fortieth","fiftieth","sixtieth","seventieth","eightieth","ninetieth"};
        for(int i=0;i<tens.length;i++){WORDS.put(tens[i],20+i*10);WORDS.put(tensOrdinals[i],20+i*10);}
    }
    private static final String WORD="(?:"+String.join("|",WORDS.keySet())+"|hundred|hundredth|thousand|thousandth)";
    private static final String NUM="(?:\\d+(?:,\\d{3})*(?:\\.\\d+)?(?:st|nd|rd|th)?|"+WORD+"(?:(?:[ -]+(?:and[ -]+)?)"+WORD+")*)";
    private static final String UNSUPPORTED="(?i)\\b(?:please confirm|please provide|please advise|whether|pending|unknown|unconfirmed|undetermined|outstanding|not supplied|not provided|proposed|proposal|would|could|might|at least|at most|minimum|maximum|between|up to|more than|less than)\\b";

    static boolean supported(String key,Object value,String quote) {
        BigDecimal candidate;
        try{candidate=new BigDecimal(String.valueOf(value));}catch(NumberFormatException invalid){return false;}
        List<String> expressions=new ArrayList<>();
        if("advanceRepaymentMonths".equals(key)||"contractPeriodMonths".equals(key))
            expressions.add("(?<![\\p{L}\\p{N}.+\\-−])("+NUM+")\\s+(?:(?:consecutive|calendar)\\s+)?months?\\b");
        else if("advanceRepaymentFirstCertificate".equals(key)) {
            expressions.add("(?<![\\p{L}\\p{N}.+\\-−])("+NUM+")\\s+(?:interim\\s+)?payment\\s+certificates?\\b");
            expressions.add("\\b(?:first\\s+repayment\\s+|interim\\s+|payment\\s+)?certificate(?:\\s+(?:number|no\\.?))?\\s*[:=#]?\\s*("+NUM+")(?![\\p{L}\\p{N}])");
        } else expressions.add("(?<![\\p{L}\\p{N}.+\\-−])(\\d+(?:,\\d{3})*(?:\\.\\d+)?)(?![\\p{L}\\p{N}]|\\.\\d)");
        for(String passage:quote.split("\\R|\\||;|(?<=[.!?])\\s+(?=[A-Z])")) {
            String unasserted=expressions.size()>1||"advanceRepaymentMonths".equals(key)||"contractPeriodMonths".equals(key)?UNSUPPORTED:
                    "(?i)\\b(?:please confirm|please provide|please advise|whether|pending|unknown|unconfirmed|undetermined|outstanding|not supplied|not provided|proposed|proposal)\\b";
            if(passage.contains("?")||Pattern.compile(unasserted).matcher(passage).find())continue;
            for(String expression:expressions) {
                Matcher match=Pattern.compile(expression,Pattern.CASE_INSENSITIVE).matcher(passage);
                while(match.find()) {
                    if(!roleSupported(key,passage,match.start(),match.end()))continue;
                    // A quoted rejection of an amount is not support for that amount.
                    String before=passage.substring(0,match.start()).toLowerCase(Locale.ROOT);
                    String after=passage.substring(match.end()).toLowerCase(Locale.ROOT);
                    if("photocopyRateUpToA3".equals(key)||"photocopyRateAboveA3".equals(key))
                        before=before.replaceAll("\\b(?:no larger than|not exceeding)\\s+a3\\b","");
                    if(before.matches("(?s).*\\b(?:not|never|no|exclude|excluding)\\b[^,;:.]{0,50}$")||
                            after.matches("(?s)^\\s*(?:is|are)?\\s*(?:not|excluded|unconfirmed)\\b.*"))continue;
                    BigDecimal literal=literal(match.group(1));
                    if(literal!=null&&candidate.compareTo(literal)==0)return true;
                }
            }
        }
        return false;
    }

    private static boolean roleSupported(String key,String passage,int from,int to) {
        String before=passage.substring(0,from).toLowerCase(Locale.ROOT);
        if("advanceRepaymentMonths".equals(key)||"advanceRepaymentFirstCertificate".equals(key)||"contractPeriodMonths".equals(key)) {
            Matcher roles=Pattern.compile("\\b(?:(?<other>(?:defects?(?:\\s+liability)?|maintenance|warranty|guarantee|validity)(?:\\s+(?:period|duration))?)|(?<repay>repay(?:ment|ments|ing)?|repaid)|(?<contract>contract\\s+(?:period|duration)|accepted\\s+contract\\s+period)|(?<generic>period|duration|completion|programme|program))\\b").matcher(before);
            String role=null;
            while(roles.find()) {
                if(roles.group("other")!=null)role="other";
                else if(roles.group("repay")!=null)role="repayment";
                else if(roles.group("contract")!=null)role="contract";
                else if(role==null)role="contract";
            }
            return "contractPeriodMonths".equals(key)?"contract".equals(role):"repayment".equals(role);
        }
        if("photocopyRateUpToA3".equals(key)||"photocopyRateAboveA3".equals(key)) {
            Pattern sizes=Pattern.compile("(?i)\\b(?:(?<above>above|exceeding|larger than|over|greater than)\\s+A3|(?<below>up to(?:\\s+and\\s+including)?|(?:of|at|or)\\s+(?:or\\s+)?below|not exceeding|no larger than)\\s+A3|(?<small>A4(?:\\s+(?:and|or)\\s+A3)?|A3\\s+(?:and|or)\\s+(?:below|smaller)))\\b");
            Matcher sizesBefore=sizes.matcher(before);Boolean above=null;
            while(sizesBefore.find())above=sizesBefore.group("above")!=null;
            if(above==null) {
                Matcher sizesAfter=sizes.matcher(passage.substring(to));
                if(sizesAfter.find())above=sizesAfter.group("above")!=null;
            }
            return above!=null&&above=="photocopyRateAboveA3".equals(key);
        }
        return true;
    }

    static BigDecimal literal(String token) {
        String normalized=token.toLowerCase(Locale.ROOT).replace(",","").trim();
        if(normalized.matches("\\d+(?:\\.\\d+)?(?:st|nd|rd|th)?"))return new BigDecimal(normalized.replaceFirst("(?:st|nd|rd|th)$",""));
        int total=0,group=0,previous=-1;boolean scaled=false;
        for(String word:normalized.split("[ -]+")) {
            if("and".equals(word)){if(group==0)return null;continue;}
            if("hundred".equals(word)||"hundredth".equals(word)) {
                if(group<1||group>9||scaled)return null;group*=100;scaled=true;previous=-1;continue;
            }
            if("thousand".equals(word)||"thousandth".equals(word)) {
                if(group<1||total!=0)return null;total=group*1000;group=0;scaled=false;previous=-1;continue;
            }
            Integer n=WORDS.get(word);if(n==null)return null;
            if(previous>=0&&!(previous>=20&&previous%10==0&&n>0&&n<10))return null;
            group+=n;previous=n;
        }
        return BigDecimal.valueOf(total+group);
    }
}
