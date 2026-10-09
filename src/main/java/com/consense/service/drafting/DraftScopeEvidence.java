package com.consense.service.drafting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Direct business scope relationships; an actor's responsibility is not the object's use. */
final class DraftScopeEvidence {
    private DraftScopeEvidence() { }
    private static final Pattern UNSETTLED=Pattern.compile("(?i)\\b(?:please confirm|please provide|whether|if|unless|pending|unknown|unconfirmed|proposed|option|might|could|subject to|do not (?:use|adopt|accept)|not (?:yet )?(?:been )?(?:confirmed|adopted|approved|selected))\\b|\\?");
    private static final Pattern OTHER_PROJECT=Pattern.compile("(?i)\\b(?:another|other|different|previous|former|neighbouring|neighboring|adjacent)\\s+(?:project|contract)\\b");
    private static final Pattern FORBIDDEN=Pattern.compile("(?i)\\bdo not (?:use|adopt|accept)\\b");
    private static final Pattern CURRENT_PROJECT_HEADING=Pattern.compile("(?i)^\\s*(?:for\\s+)?(?:this|current|our)\\s+(?:project|contract)\\s*:\\s*$");
    private static final String PASSAGES="\\R|;|(?<=[.!?])\\s+(?=[A-Z])";
    private static final String CLAUSES="(?i)(?:,\\s*)?\\b(?:and|but|whereas|while)\\s+(?=(?:the|a|an|this|that|another|other|our|no)\\s+[^,;.!?\\r\\n]{0,80}\\b(?:is|are|will|shall|serves?|supports?|provides?|inspects?|has|have|requires?|required|remains)\\b)";
    private static final Pattern FOOTING_LABEL=Pattern.compile("(?i)\\bfootings\\s+(?:serve|serving|service)\\s+(?:buildings\\s+or\\s+major\\s+external\\s+structures|classification)\\s*[:=]\\s*(yes|true|no|false)\\b");
    private static final String FOOTING_POSTMODIFIER="(?:\\s+(?:(?:listed|recorded|identified|shown)\\s+)?(?:in|on)\\s+(?:(?:this|the|our)\\s+)?(?:(?:approved|current|project|contract)\\s+){0,2}(?:register|schedule|list|drawing|table))?";
    private static final Pattern FOOTING_OBJECT=Pattern.compile("(?i)\\bfootings"+FOOTING_POSTMODIFIER+"\\s+(?:therefore\\s+)?(?:(?:will|shall|must)\\s+)?(?:(?:are|be)\\s+(?:(?:designed|constructed|used)\\s+)?)?(?:serve|serves|serving|for|support|supports|supporting)\\s+(?:the\\s+)?(?:(?!(?:and|or|provides?|supplies?|inspects?)\\b)[a-z-]+\\s+){0,8}(?:buildings?|major\\s+external\\s+structures?)\\b");
    private static final Pattern FOOTING_NEGATIVE=Pattern.compile("(?i)\\bfootings\\s+(?:(?:do\\s+not|will\\s+not|shall\\s+not|never)\\s+serve\\s+(?:(?:a|any|the)\\s+)?buildings?\\s+(?:or|and)|(?:will\\s+|shall\\s+)?serve\\s+neither\\s+(?:(?:a|any|the)\\s+)?buildings?\\s+nor)\\s+(?:(?:a|any|the)\\s+)?major\\s+external\\s+structures?\\b");
    private static final Pattern FOOTING_SUBJECT=Pattern.compile("(?i)^\\s*(?:for\\s+(?:this|our|the current)\\s+(?:project|contract),\\s*)?(?:the\\s+)?(?:(?:listed|shallow|pad|strip|reinforced|concrete|new|project)\\s+){0,3}footings"+FOOTING_POSTMODIFIER+"\\s+(?:therefore\\s+)?(?:are|were|will\\s+be|shall\\s+be|serve|support|do\\s+not|will\\s+not|shall\\s+not)\\b");
    private static final Pattern COMPETING_ANTECEDENT=Pattern.compile("(?i)\\b(?:architects?|contractors?|engineers?|piles|pile\\s*caps|walls|columns|temporary supports)\\b|[,;]\\s*(?:who|which|whose)\\b");
    private static final String SITE_POSITION="(?:(?:north|south|east|west)\\s+)?";
    private static final String SITE_PAIR="(?:"+SITE_POSITION+"building\\s+(?:sites?\\s+)?and\\s+(?:the\\s+)?"+SITE_POSITION+"demolition\\s+sites?|"+SITE_POSITION+"demolition\\s+(?:sites?\\s+)?and\\s+(?:the\\s+)?"+SITE_POSITION+"building\\s+sites?)";
    private static final Pattern SITES_POSITIVE=Pattern.compile("(?i)\\b"+SITE_PAIR+"\\s+(?:(?:is|are|remain|will\\s+be)\\s+(?:physically\\s+)?(?:separate|separated|detached)|(?:form|forms|will\\s+form)\\s+(?:a\\s+)?(?:separate|detached))\\b");
    private static final Pattern SITES_NEGATIVE=Pattern.compile("(?i)\\b"+SITE_PAIR+"\\s+(?:(?:is|are|remain|will\\s+be)\\s+not\\s+(?:physically\\s+)?(?:separate|separated|detached)|(?:do|does|will)\\s+not\\s+form\\s+(?:a\\s+|the\\s+)?(?:separate|detached))\\b");
    private static final Pattern SITE_FROM_SITE=Pattern.compile("(?i)\\b(?:building|demolition)\\s+sites?\\s+(?:is|are|will\\s+be)\\s+(not\\s+)?(?:physically\\s+)?(?:separated|detached)\\s+from\\s+(?:the\\s+)?(building|demolition)\\s+sites?\\b");
    private static final Pattern LOCATION_LABEL=Pattern.compile("(?i)\\bproject\\s+in\\s+tin\\s+shui\\s+wai\\s*[:=]\\s*(yes|true|no|false)\\b");
    private static final Pattern DEPENDENT=Pattern.compile("(?i)^\\s*(?:this|that)(?:\\s+(?:decision|classification|scope|relationship|project location|selection))?\\s+(?:is|remains|has)\\b");
    private static final Pattern GENERIC_DECISION=Pattern.compile("(?i)(?:^|:)\\s*(?:(?:the|this|that)\\s+)?(?:classification|decision)\\s+(?:is|remains|has|awaits|needs|requires)\\b");
    private static final Pattern POSSESSIVE_QUALIFIER=Pattern.compile("(?i)^\\s*(?:its|their)\\s+(classification|decision|scope|relationship|project location|location|contact)\\s+(?:is|remains|has|awaits|needs|requires)\\b");
    private static final Pattern DOMESTIC_SUBJECT=Pattern.compile("(?i)^\\s*(?:(?:the|this|our)\\s+)?(?:(?:staff|caretaker|resident|domestic|residential|accommodation|housing)\\s+){0,4}(?:housing|accommodation|blocks?|buildings?)\\b|^\\s*(?:the\\s+)?(?:project\\s+scope|works|contract\\s+scope)\\s+(?:describes|includes?|comprises?|consists\\s+of)\\b");
    private static final Pattern LOCATED_SUBJECT=Pattern.compile("(?i)^\\s*(?:no\\s+(?:part|portion|area)\\s+of\\s+)?(?:(?:the|this|our|current)\\s+)?(?:project|contract|works)\\b");
    private static final String PROJECT_SUBJECT="(?:project|contract|works)(?:\\s+and\\s+(?:its|the)\\s+(?:[a-z-]+\\s+){0,5}(?:block|building|works))?";

    static boolean footingsSupported(Object value,String quote) {
        return footingsSupported(value,quote,quote,quote);
    }

    static boolean footingsSupported(Object value,String quote,String supplied,String original) {
        Boolean requested=DraftBusinessRules.truth(value);
        String scope=anchoredScope(quote,supplied,original,"footings?");
        return requested!=null&&scope!=null&&!unassertedScope(scope,"footings?")&&
                requested.equals(footingsQuoteAnswer(quote,scope))&&requested.equals(footingsAnswer(scope));
    }

    /** A short They citation may retain the sole immediately preceding footings subject in its source. */
    private static Boolean footingsQuoteAnswer(String quote,String scope) {
        Boolean direct=footingsAnswer(quote);
        if(direct!=null||!quote.trim().matches("(?is)^they\\b.*"))return direct;
        boolean footingsSubject=false;
        String anchor=DraftEvidenceQuotes.textIdentity(quote);
        for(String paragraph:scope.split("\\R[\\t ]*\\R")) {
            footingsSubject=false;
            for(String passage:paragraph.split(PASSAGES+"|"+CLAUSES)) {
                if(DraftEvidenceQuotes.textIdentity(passage).equals(anchor))
                    return footingsSubject?footingsAnswer(passage.replaceFirst("(?i)^\\s*they\\b","The footings")):null;
                footingsSubject=FOOTING_SUBJECT.matcher(passage).find()&&!COMPETING_ANTECEDENT.matcher(passage).find()&&
                        !UNSETTLED.matcher(passage).find()&&!OTHER_PROJECT.matcher(passage).find();
            }
        }
        return null;
    }

    private static Boolean footingsAnswer(String text) {
        Boolean answer=null;
        for(String sentence:sourcePassages(resolveFootingPronouns(text))) {
            if(UNSETTLED.matcher(sentence).find()||OTHER_PROJECT.matcher(sentence).find())continue;
            String s=sentence.toLowerCase(Locale.ROOT);
            Matcher label=FOOTING_LABEL.matcher(s);boolean explicitLabel=label.find();
            Matcher objectMatch=FOOTING_OBJECT.matcher(s);
            boolean negative=FOOTING_NEGATIVE.matcher(s).find(),object=objectMatch.find();
            // A negation of this served-object relationship cannot supply the positive classification.
            if(!explicitLabel&&!negative&&(!object||Pattern.compile("(?i)\\b(?:not|never|no|except|excluding)\\b").matcher(objectMatch.group()).find()))continue;
            boolean candidate=explicitLabel?label.group(1).matches("yes|true"):!negative;
            if(answer!=null&&answer!=candidate)return null;answer=candidate;
        }
        return answer;
    }

    /** Interpret a leading They only after an explicit sole footings subject in this paragraph. */
    private static String resolveFootingPronouns(String text) {
        StringBuilder resolved=new StringBuilder();
        for(String paragraph:text.split("\\R[\\t ]*\\R")) {
            boolean footingsSubject=false;
            for(String passage:paragraph.split(PASSAGES+"|"+CLAUSES)) {
                String interpreted=footingsSubject?passage.replaceFirst("(?i)^\\s*they\\b","The footings"):passage;
                resolved.append(interpreted).append('\n');
                footingsSubject=FOOTING_SUBJECT.matcher(passage).find()&&!COMPETING_ANTECEDENT.matcher(passage).find()&&
                        !UNSETTLED.matcher(passage).find()&&!OTHER_PROJECT.matcher(passage).find();
            }
            resolved.append('\n');
        }
        return resolved.toString();
    }

    static boolean buildingDemolitionSeparationSupported(Object value,String quote,String supplied,String original) {
        Boolean requested=DraftBusinessRules.truth(value);
        String scope=assertedScope(quote,supplied,original,"(?:building|demolition|sites?|parcels?)");
        return requested!=null&&scope!=null&&!adjacentSeparationClassificationPending(scope,original)&&
                requested.equals(separationAnswer(quote))&&requested.equals(separationAnswer(scope));
    }

    /** An immediately following qualification belongs to this explicit separation assertion. */
    private static boolean adjacentSeparationClassificationPending(String scope,String original) {
        boolean previousSeparation=false;
        for(String passage:sourcePassages(scope)) {
            if(previousSeparation&&pendingSeparationClassification(passage))return true;
            previousSeparation=separationAnswer(passage)!=null;
        }
        if(previousSeparation) {
            String[] paragraphs=original.split("\\R[\\t ]*\\R");
            for(int index=0;index+1<paragraphs.length;index++)
                if(DraftEvidenceQuotes.textIdentity(paragraphs[index]).equals(DraftEvidenceQuotes.textIdentity(scope))) {
                    List<String> next=sourcePassages(paragraphs[index+1]);
                    if(!next.isEmpty()&&pendingSeparationClassification(next.get(0)))return true;
                }
        }
        return false;
    }

    private static boolean pendingSeparationClassification(String passage) {
        return passage.trim().matches("(?is)(?:the\\s+)?separation\\s+classification\\s+(?:is|remains)\\b.*")&&
                UNSETTLED.matcher(passage).find();
    }

    private static Boolean separationAnswer(String text) {
        Boolean answer=null;
        for(String sentence:sourcePassages(text)) {
            if(UNSETTLED.matcher(sentence).find()||OTHER_PROJECT.matcher(sentence).find())continue;
            Boolean candidate=null;
            if(SITES_NEGATIVE.matcher(sentence).find())candidate=false;
            if(SITES_POSITIVE.matcher(sentence).find()) {
                if(candidate!=null)return null;
                candidate=true;
            }
            Matcher from=SITE_FROM_SITE.matcher(sentence);
            while(from.find()) {
                // A building site's separation from another building site does not answer this relation.
                if(from.group().toLowerCase(Locale.ROOT).startsWith(from.group(2).toLowerCase(Locale.ROOT)))continue;
                boolean related=from.group(1)==null;
                if(candidate!=null&&candidate!=related)return null;
                candidate=related;
            }
            if(candidate==null)continue;
            if(answer!=null&&!answer.equals(candidate))return null;
            answer=candidate;
        }
        return answer;
    }

    static String assertedScope(String quote,String supplied,String original,String subject) {
        String scope=anchoredScope(quote,supplied,original,subject);
        return scope!=null&&!unassertedScope(scope,subject)?scope:null;
    }

    /** Tables/lists may span native paragraphs; each quoted paragraph retains its original source role. */
    static String assertedCollectionScope(String quote,String supplied,String original,String subject) {
        if(quote==null||supplied==null||original==null||quote.trim().isEmpty())return null;
        String source=DraftEvidenceQuotes.textIdentity(original),anchor=DraftEvidenceQuotes.textIdentity(quote);
        int at=source.indexOf(anchor),end=at+anchor.length(),cursor=0;
        if(at<0||source.indexOf(anchor,at+anchor.length())>=0)return null;
        StringBuilder scope=new StringBuilder();
        for(String paragraph:original.split("\\R[\\t ]*\\R")) {
            String normalized=DraftEvidenceQuotes.textIdentity(paragraph);
            if(normalized.isEmpty())continue;
            int start=source.indexOf(normalized,cursor),stop=start+normalized.length();cursor=stop;
            if(start>=end)break;
            if(stop<=at)continue;
            String asserted=assertedScope(paragraph,supplied,original,subject);
            if(asserted==null)return null;
            if(scope.length()>0)scope.append("\n\n");scope.append(asserted);
        }
        return scope.length()==0?null:scope.toString();
    }

    static boolean tinShuiWaiSupported(Object value,String quote) {
        return tinShuiWaiSupported(value,quote,quote,quote);
    }

    static boolean tinShuiWaiSupported(Object value,String quote,String supplied,String original) {
        Boolean requested=DraftBusinessRules.truth(value);
        String scope=anchoredScope(quote,supplied,original,"(?:tin shui wai|project (?:location|scope))");
        return requested!=null&&scope!=null&&!unassertedScope(scope,"(?:tin shui wai|project (?:location|scope))")&&
                requested.equals(locationAnswer(quote))&&requested.equals(locationAnswer(scope));
    }

    private static Boolean locationAnswer(String text) {
        Boolean answer=null;
        for(String sentence:sourcePassages(text)) {
            if(UNSETTLED.matcher(sentence).find()||OTHER_PROJECT.matcher(sentence).find())continue;
            String s=sentence.toLowerCase(Locale.ROOT);
            if(!s.contains("tin shui wai"))continue;
            Matcher label=LOCATION_LABEL.matcher(s);boolean labelled=label.find();
            boolean absent=s.matches("(?s).*\\b(?:no\\s+(?:project\\s+)?works\\s+(?:are\\s+)?in|no\\s+(?:part|portion|area)\\s+of\\s+(?:(?:this|our|the current)\\s+)?(?:project|contract|works)\\s+(?:is|are)\\s+(?:located\\s+)?in|"+PROJECT_SUBJECT+"\\s+(?:is|are)\\s+not\\s+(?:located\\s+)?in|project\\s+is\\s+(?:located\\s+)?in\\s+[^.;]{0,80}\\brather\\s+than)\\s+tin\\s+shui\\s+wai\\b.*");
            boolean located=s.matches("(?s).*\\b"+PROJECT_SUBJECT+"\\s+(?:is|are|will be)\\s+(?:(?:located|situated)\\s+)?(?:in|at)\\s+tin\\s+shui\\s+wai\\b.*")||
                    s.matches("(?s).*\\bcontract\\s+no\\.?\\s+\\d+\\s*,\\s*construction\\b[^;]{0,160}\\b(?:in|at)\\s+tin\\s+shui\\s+wai\\b.*");
            String polarity=s.replaceAll("\\bno\\.?(?=\\s*\\d)","number");
            if(!labelled&&!absent&&(!located||polarity.matches("(?s).*\\b(?:not|no|outside|except|excluding|adjacent|near|neighbouring|neighboring|previous|another)\\b.*")))continue;
            boolean candidate=labelled?label.group(1).matches("yes|true"):!absent;
            if(answer!=null&&answer!=candidate)return null;answer=candidate;
        }
        return answer;
    }

    /** The quote keeps its original paragraph and visible qualifiers; an unseen prefix cannot be discarded. */
    private static String anchoredScope(String quote,String supplied,String original,String subject) {
        if(quote==null||supplied==null||original==null||quote.trim().isEmpty())return null;
        String anchor=DraftEvidenceQuotes.textIdentity(quote),scope=null;
        String[] paragraphs=original.split("\\R[\\t ]*\\R");
        for(int index=0;index<paragraphs.length;index++) {
            String paragraph=paragraphs[index];
            String normalized=DraftEvidenceQuotes.textIdentity(paragraph);int position=normalized.indexOf(anchor);
            if(position<0)continue;
            if(scope!=null||normalized.indexOf(anchor,position+anchor.length())>=0)return null;
            // A model substring must retain the project role of its own original sentence prefix.
            String before=normalized.substring(0,position),prefix=before;
            Matcher sentenceBoundary=Pattern.compile("(?<=[.!?])\\s+(?=[A-Z])|;").matcher(before);
            while(sentenceBoundary.find())prefix=before.substring(sentenceBoundary.end());
            if(OTHER_PROJECT.matcher(prefix).find())return null;
            // Neutral heading paragraphs do not sever an outer source role. A visible current-project
            // heading may reset an other-project role, but cannot adopt a pending or forbidden answer.
            if(governingHeadingChain(paragraphs,index))return null;
            if(!DraftEvidenceQuotes.present(supplied,paragraph))return null;
            scope=paragraph;
            // Keep the complete continuous dependent unit, including an adopted intermediate note.
            // An independent next topic ends the unit and cannot lend its later qualifier to this quote.
            for(int dependent=index+1;dependent<paragraphs.length;dependent++) {
                String next=paragraphs[dependent];Matcher possessive=POSSESSIVE_QUALIFIER.matcher(next);
                boolean related=possessiveScope(scope,subject).related;
                boolean relatedPossessive=possessive.find()&&possessiveProperty(subject,possessive.group(1))&&related;
                boolean qualifierHeading=false;
                if(related&&relatedQualifierHeading(next,subject)) {
                    int following=dependent+1;
                    while(following<paragraphs.length&&relatedQualifierHeading(paragraphs[following],subject))following++;
                    if(following<paragraphs.length) {
                        Matcher afterHeading=POSSESSIVE_QUALIFIER.matcher(paragraphs[following]);
                        qualifierHeading=DEPENDENT.matcher(paragraphs[following]).find()||afterHeading.find()&&possessiveProperty(subject,afterHeading.group(1));
                    }
                }
                if(!DEPENDENT.matcher(next).find()&&!relatedPossessive&&!qualifierHeading)break;
                scope+="\n\n"+next;
            }
            if(!DraftEvidenceQuotes.present(supplied,scope))return null;
        }
        return scope;
    }

    private static boolean unassertedScope(String scope,String subject) {
        if(possessiveScope(scope,subject).pending)return true;
        Pattern field=Pattern.compile("(?i)\\b(?:"+subject+")\\b");
        for(String passage:sourcePassages(scope)) {
            if(OTHER_PROJECT.matcher(passage).find())continue;
            if(UNSETTLED.matcher(passage).find()&&(field.matcher(passage).find()||DEPENDENT.matcher(passage).find()||GENERIC_DECISION.matcher(passage).find()))return true;
        }
        return false;
    }

    private static final class PossessiveScope { boolean related;boolean pending; }

    /** A possessive qualification belongs only to its immediately previous explicit field subject. */
    private static PossessiveScope possessiveScope(String scope,String subject) {
        PossessiveScope state=new PossessiveScope();
        String interpreted=subject.contains("footing")?resolveFootingPronouns(scope):scope;
        for(String passage:sourcePassages(interpreted,true)) {
            if(passage.startsWith("\u0000")) {
                if(!relatedQualifierHeading(passage.substring(1),subject))state.related=false;
                continue;
            }
            if(OTHER_PROJECT.matcher(passage).find()) {state.related=false;continue;}
            Matcher qualifier=POSSESSIVE_QUALIFIER.matcher(passage);
            if(qualifier.find()) {
                if(!possessiveProperty(subject,qualifier.group(1))) {state.related=false;continue;}
                if(state.related&&UNSETTLED.matcher(passage).find())state.pending=true;
                continue;
            }
            if(DEPENDENT.matcher(passage).find())continue;
            // The native no-parcel continuation preserves its already explicit two-site relationship.
            if(state.related&&subject.contains("sites?")&&passage.trim().matches("(?i)no parcel contains both types of work\\.?"))continue;
            state.related=soleFieldSubject(passage,subject);
        }
        return state;
    }

    private static boolean relatedQualifierHeading(String heading,String subject) {
        String h=heading.trim();
        return h.endsWith(":")&&!UNSETTLED.matcher(h).find()&&!OTHER_PROJECT.matcher(h).find()&&
                (CURRENT_PROJECT_HEADING.matcher(h).matches()||h.matches("(?i)(?:scope|classification|decision|relationship):")||
                        Pattern.compile("(?i)\\b(?:"+subject+")\\b").matcher(h).find());
    }

    private static boolean possessiveProperty(String subject,String property) {
        return property.matches("(?i)classification|decision|scope|relationship")||
                subject.contains("tin shui wai")&&property.matches("(?i)(?:project )?location");
    }

    private static boolean soleFieldSubject(String passage,String subject) {
        if(UNSETTLED.matcher(passage).find()||OTHER_PROJECT.matcher(passage).find())return false;
        if(subject.contains("footing"))return FOOTING_SUBJECT.matcher(passage).find()&&!COMPETING_ANTECEDENT.matcher(passage).find();
        if(subject.contains("domestic"))return DOMESTIC_SUBJECT.matcher(passage).find()&&DraftBooleanEvidence.domesticScope(passage)!=null;
        if(subject.contains("tin shui wai"))return LOCATED_SUBJECT.matcher(passage).find()&&locationAnswer(passage)!=null;
        if(subject.contains("quantities"))return passage.trim().matches("(?is)^(?:it\\s+is\\s+not\\s+the\\s+case\\s+that\\s+)?(?:not\\s+)?(?:all\\s+(?:the\\s+)?)?(?:BQ\\s+(?:Bill\\s+)?)?quantities\\b.*")&&DraftBooleanEvidence.allBqQuantities(passage)!=null;
        if(subject.contains("sites?"))return separationAnswer(passage)!=null&&
                Pattern.compile("(?i)^\\s*(?:the\\s+)?(?:"+SITE_PAIR+"|(?:building|demolition)\\s+sites?)\\b").matcher(passage).find();
        return false;
    }

    /** A source-role heading applies to its own body; splitting a line cannot discard it. */
    static List<String> sourcePassages(String text) {
        return sourcePassages(text,false);
    }

    private static List<String> sourcePassages(String text,boolean headingBoundaries) {
        List<String> passages=new ArrayList<>();
        String precedingHeading=null;
        for(String paragraph:text.split("\\R[\\t ]*\\R")) {
            if(governingHeading(paragraph)) {precedingHeading=paragraph;if(headingBoundaries)passages.add("\u0000"+paragraph);continue;}
            String heading=precedingHeading;boolean hasBody=false;
            for(String passage:scopePassages(paragraph)) {
                if(passage.trim().isEmpty())continue;
                if(passage.trim().endsWith(":")) {
                    if(headingBoundaries)passages.add("\u0000"+passage);
                    if(governingHeading(passage))heading=passage;
                    else if(CURRENT_PROJECT_HEADING.matcher(passage).matches()&&
                            (heading==null||!UNSETTLED.matcher(heading).find()))heading=null;
                    continue;
                }
                passages.add(heading==null?passage:heading+" "+passage);
                hasBody=true;
            }
            // Keep the role through heading-only paragraphs, then end it at its complete body.
            precedingHeading=hasBody?null:heading;
        }
        return passages;
    }

    /** The conjunction inside an explicit two-site subject does not start a new assertion. */
    private static List<String> scopePassages(String paragraph) {
        List<String> passages=new ArrayList<>();int start=0;
        Matcher boundary=Pattern.compile(PASSAGES+"|"+CLAUSES).matcher(paragraph);
        while(boundary.find()) {
            Matcher pair=Pattern.compile("(?i)\\b"+SITE_PAIR+"\\b").matcher(paragraph);boolean compound=false;
            while(pair.find())if(pair.start()<=boundary.start()&&boundary.end()<=pair.end()) {compound=true;break;}
            if(compound)continue;
            passages.add(paragraph.substring(start,boundary.start()));start=boundary.end();
        }
        passages.add(paragraph.substring(start));return passages;
    }

    private static boolean governingHeadingChain(String[] paragraphs,int bodyIndex) {
        boolean currentProject=false;
        for(int index=bodyIndex-1;index>=0;index--) {
            String heading=paragraphs[index].trim();
            if(!heading.endsWith(":"))break;
            if(UNSETTLED.matcher(heading).find())return true;
            if(OTHER_PROJECT.matcher(heading).find()&&!currentProject)return true;
            if(CURRENT_PROJECT_HEADING.matcher(heading).matches())currentProject=true;
        }
        return false;
    }

    private static boolean governingHeading(String text) {
        return text.trim().endsWith(":")&&(OTHER_PROJECT.matcher(text).find()||UNSETTLED.matcher(text).find());
    }
}
